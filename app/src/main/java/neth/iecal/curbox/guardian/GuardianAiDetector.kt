package neth.iecal.curbox.guardian

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runtime configuration for the detector. The UI/service sets these values
 * (M3 wires them to the Curbox settings store). Volatile fields + a single
 * data class keep the port Hilt-free and simple.
 */
data class GuardianConfig(
    var aiEnabled: Boolean = false,
    var userGender: String = "NONE",   // "NONE" | "MALE" | "FEMALE"
    var aiThreshold: Float = 0.72f,     // legacy combined classifier
    var nsfwGateThreshold: Float = 0.68f,
    var genderThreshold: Float = 0.78f,
    var gridVoteCount: Int = 2
)

/**
 * On-device NSFW detection engine (TFLite).
 *
 * Ported from Dogs-of-KAHAF `AiDetector`, adapted to Curbox style (no Hilt,
 * no Timber). Includes the Phase-1 false-block fixes:
 *  1. opposite-gender blocking always requires the FULL gender confidence
 *     (the "soft NSFW" path that lowered it to 0.62 is removed);
 *  2. full-screen callers pass requireStrongNsfw=true, raising the NSFW gate
 *     to 0.80 before the gender model runs on a noisy whole screenshot.
 *
 * Three models, all optional (each feature fail-opens to "no block"):
 *  - guardian_model.tflite : legacy combined classifier
 *  - nsfw_model.tflite     : dedicated NSFW gate
 *  - gender_model.tflite   : male/female classifier (2 outputs)
 */
class GuardianAiDetector(private val context: Context) {

    private val tag = "GuardianAi"

    private val inferenceLock = Mutex()
    private var legacyInterpreter: Interpreter? = null
    private var nsfwInterpreter: Interpreter? = null
    private var genderInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Reusable buffers to reduce GC pressure.
    private var inputBuffer: ByteBuffer? = null
    private var pixelsArray: IntArray? = null

    @Volatile
    private var consecutiveInferenceFails = 0
    private val inferenceFailThreshold = 3

    /** Live config snapshot. Mutate fields from the main thread / settings UI. */
    @Volatile
    var config: GuardianConfig = GuardianConfig()

    // ── model lifecycle ────────────────────────────────────────────────────

    fun isLegacyAvailable(): Boolean = legacyInterpreter != null
    fun isGenderModelAvailable(): Boolean = genderInterpreter != null
    fun isNsfwGateAvailable(): Boolean = nsfwInterpreter != null

    suspend fun ensureLoaded() {
        inferenceLock.withLock {
            if (legacyInterpreter == null) legacyInterpreter = tryLoad(GuardianConstants.MODEL_LEGACY)
            if (nsfwInterpreter == null) nsfwInterpreter = tryLoad(GuardianConstants.MODEL_NSFW)
            if (genderInterpreter == null) genderInterpreter = tryLoad(GuardianConstants.MODEL_GENDER)
        }
    }

    fun reloadModels() {
        // Called after importing a new model file: drop + lazily reload next use.
        try { legacyInterpreter?.close() } catch (_: Throwable) {}
        try { nsfwInterpreter?.close() } catch (_: Throwable) {}
        try { genderInterpreter?.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        legacyInterpreter = null
        nsfwInterpreter = null
        genderInterpreter = null
        gpuDelegate = null
    }

    fun close() {
        try { legacyInterpreter?.close() } catch (_: Throwable) {}
        try { nsfwInterpreter?.close() } catch (_: Throwable) {}
        try { genderInterpreter?.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        legacyInterpreter = null
        nsfwInterpreter = null
        genderInterpreter = null
        gpuDelegate = null
        inputBuffer = null
        pixelsArray = null
    }

    private fun tryLoad(name: String, forceCpu: Boolean = false): Interpreter? {
        return try {
            val buffer = loadModelBuffer(name) ?: return null
            buildInterpreter(buffer, forceCpu).also {
                Log.i(tag, "Loaded: $name (cpu=$forceCpu)")
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to load $name", t)
            null
        }
    }

    private fun loadModelBuffer(name: String): ByteBuffer? {
        val f = File(context.filesDir, name)
        if (f.exists() && f.length() > 0) {
            return try {
                FileInputStream(f).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                } as MappedByteBuffer
            } catch (t: Throwable) {
                Log.w(tag, "mmap failed; byte copy $name", t)
                val bytes = f.readBytes()
                ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
            }
        }
        return try {
            context.assets.open(name).use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) return null
                ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
            }
        } catch (_: Throwable) { null }
    }

    private fun buildInterpreter(buffer: ByteBuffer, forceCpu: Boolean = false): Interpreter {
        val opts = Interpreter.Options()
        if (!forceCpu) {
            try {
                val cl = CompatibilityList()
                if (cl.isDelegateSupportedOnThisDevice) {
                    gpuDelegate = GpuDelegate()
                    opts.addDelegate(gpuDelegate)
                } else {
                    opts.setNumThreads(2)
                }
            } catch (t: Throwable) {
                Log.w(tag, "GPU init failed; CPU fallback", t)
                opts.setNumThreads(2)
            }
        } else {
            opts.setNumThreads(2)
        }
        return try {
            Interpreter(buffer, opts)
        } catch (t: Throwable) {
            Log.w(tag, "Interp build failed; CPU retry", t)
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null
            Interpreter(buffer, Interpreter.Options().setNumThreads(2))
        }
    }

    // ── detection ──────────────────────────────────────────────────────────

    /** Legacy combined-classifier block (with grid voting for large images). */
    suspend fun isUnsafe(bitmap: Bitmap): Boolean {
        val interp = legacyInterpreter ?: return false
        return inferenceLock.withLock {
            try {
                if (!isImageComplex(bitmap)) return@withLock false

                val threshold = config.aiThreshold.coerceIn(0.50f, 0.95f)
                val voteNeeded = config.gridVoteCount.coerceIn(1, 4)

                val fullScore = extractGuardianScore(
                    runInferenceSafe(interp, bitmap) ?: return@withLock false
                )

                if (fullScore < threshold * 0.3f) return@withLock false
                if (fullScore >= threshold) return@withLock true

                if (bitmap.width < 500 || bitmap.height < 500) {
                    return@withLock fullScore >= threshold
                }

                val regions = splitIntoOverlappingGrid(bitmap, cols = 4, rows = 5, overlapPercent = 0.25f)
                var triggeredCount = 0
                for (region in regions) {
                    if (!isImageComplex(region)) {
                        region.recycle()
                        continue
                    }
                    try {
                        val out = runInferenceSafe(interp, region) ?: continue
                        val score = extractGuardianScore(out)
                        if (score >= threshold) triggeredCount++
                        if (triggeredCount >= voteNeeded) break
                    } catch (t: Throwable) {
                        Log.e(tag, "Grid error", t)
                    } finally {
                        region.recycle()
                    }
                }
                triggeredCount >= voteNeeded
            } catch (t: Throwable) {
                Log.e(tag, "isUnsafe failed", t)
                false
            }
        }
    }

    /**
     * Opposite-gender NSFW block: NSFW gate first, then gender confidence.
     * requireStrongNsfw=true (full-screen scans) raises the NSFW gate to
     * [GuardianConstants.FULL_SCREEN_NSFW_GATE].
     */
    suspend fun isOppositeGenderNsfw(
        bitmap: Bitmap,
        userGender: String,
        requireStrongNsfw: Boolean = false
    ): Boolean {
        val nsfw = nsfwInterpreter ?: return false
        val gender = genderInterpreter ?: return false
        if (userGender != "MALE" && userGender != "FEMALE") return false

        return inferenceLock.withLock {
            try {
                if (!isImageComplex(bitmap)) return@withLock false

                val currentGender = config.userGender
                    .let { if (it == "MALE" || it == "FEMALE") it else userGender }
                if (currentGender != "MALE" && currentGender != "FEMALE") return@withLock false

                val nsfwGate = if (requireStrongNsfw) {
                    maxOf(config.nsfwGateThreshold, GuardianConstants.FULL_SCREEN_NSFW_GATE)
                } else {
                    config.nsfwGateThreshold
                }
                val genderConf = config.genderThreshold
                val voteNeeded = config.gridVoteCount

                val initial = runInferenceSafe(nsfw, bitmap) ?: return@withLock false
                var maxNsfwScore = extractNsfwGateScore(initial)

                if (maxNsfwScore < nsfwGate) {
                    if (bitmap.width >= 500 && bitmap.height >= 500) {
                        val regions = splitIntoOverlappingGrid(bitmap, cols = 4, rows = 5, overlapPercent = 0.25f)
                        var nsfwVotes = 0
                        for (region in regions) {
                            if (!isImageComplex(region)) {
                                region.recycle()
                                continue
                            }
                            try {
                                val out = runInferenceSafe(nsfw, region) ?: continue
                                val score = extractNsfwGateScore(out)
                                if (score > maxNsfwScore) maxNsfwScore = score
                                if (score >= nsfwGate) nsfwVotes++
                                if (nsfwVotes >= voteNeeded) break
                            } catch (t: Throwable) {
                                Log.e(tag, "NSFW grid error", t)
                            } finally {
                                region.recycle()
                            }
                        }
                        if (maxNsfwScore < nsfwGate && nsfwVotes < voteNeeded) {
                            return@withLock false
                        }
                    } else {
                        return@withLock false
                    }
                }

                val genderScores = runInferenceSafe(gender, bitmap) ?: return@withLock false
                val half = genderScores.size / 2
                val firstSum = genderScores.take(half).sum()
                val secondSum = genderScores.drop(half).sum()
                val total = (firstSum + secondSum).coerceAtLeast(0.001f)
                val femaleProb = firstSum / total
                val maleProb = secondSum / total

                // Phase-1 false-block fix: always full gender confidence
                // (the old "soft NSFW" 0.62 path is gone).
                when (currentGender) {
                    "MALE" -> femaleProb >= genderConf
                    "FEMALE" -> maleProb >= genderConf
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e(tag, "Gender NSFW failed", t)
                false
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun splitIntoOverlappingGrid(
        bitmap: Bitmap,
        cols: Int,
        rows: Int,
        overlapPercent: Float = 0f
    ): List<Bitmap> {
        val regions = mutableListOf<Bitmap>()
        val w = bitmap.width
        val h = bitmap.height
        val cellW = w / cols
        val cellH = h / rows
        val stepX = (cellW * (1f - overlapPercent)).toInt().coerceAtLeast(cellW / 2)
        val stepY = (cellH * (1f - overlapPercent)).toInt().coerceAtLeast(cellH / 2)

        var y = 0
        while (y < h) {
            val currentH = if (y + cellH > h) h - y else cellH
            if (currentH < 64) break
            var x = 0
            while (x < w) {
                val currentW = if (x + cellW > w) w - x else cellW
                if (currentW < 64) break
                runCatching {
                    regions.add(Bitmap.createBitmap(bitmap, x, y, currentW, currentH))
                }.onFailure { Log.e(tag, "Grid crop at $x,$y", it) }
                if (x + cellW >= w) break
                x += stepX
                if (x + cellW > w) x = w - cellW
            }
            if (y + cellH >= h) break
            y += stepY
            if (y + cellH > h) y = h - cellH
        }
        return regions
    }

    private fun extractGuardianScore(scores: FloatArray): Float {
        return when (scores.size) {
            1 -> scores[0]
            2 -> scores[1]
            3 -> (scores.getOrElse(1) { 0f } + scores.getOrElse(2) { 0f }).coerceAtMost(1.0f)
            5 -> maxOf(scores.getOrElse(1) { 0f }, scores.getOrElse(3) { 0f }, scores.getOrElse(4) { 0f })
            else -> scores.drop(1).max()
        }
    }

    private fun extractNsfwGateScore(scores: FloatArray): Float {
        return when (scores.size) {
            1 -> scores[0]
            2 -> scores[1]
            5 -> maxOf(scores.getOrElse(1) { 0f }, scores.getOrElse(3) { 0f }, scores.getOrElse(4) { 0f })
            else -> scores.drop(1).max()
        }
    }

    private fun isImageComplex(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64) return false
        val stepX = (w / 8).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        var count = 0
        var sumL = 0.0
        var sumL2 = 0.0
        var y = stepY
        while (y < h) {
            var x = stepX
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                sumL += luma
                sumL2 += luma * luma
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val avgL = sumL / count
        val variance = (sumL2 / count) - (avgL * avgL)
        return variance in 150.0..9000.0
    }

    private fun runInferenceSafe(interp: Interpreter, bitmap: Bitmap): FloatArray? {
        return try {
            val result = runInference(interp, bitmap)
            consecutiveInferenceFails = 0
            result
        } catch (t: Throwable) {
            consecutiveInferenceFails++
            Log.w(tag, "Inference failed (streak=$consecutiveInferenceFails)", t)
            if (consecutiveInferenceFails >= inferenceFailThreshold) {
                rebuildAllOnCpu()
            }
            null
        }
    }

    private fun rebuildAllOnCpu() {
        try {
            Log.w(tag, "Rebuilding AI interpreters on CPU after $consecutiveInferenceFails failures")
            reloadModels()
            legacyInterpreter = tryLoad(GuardianConstants.MODEL_LEGACY, forceCpu = true)
            nsfwInterpreter = tryLoad(GuardianConstants.MODEL_NSFW, forceCpu = true)
            genderInterpreter = tryLoad(GuardianConstants.MODEL_GENDER, forceCpu = true)
            consecutiveInferenceFails = 0
        } catch (t: Throwable) {
            Log.e(tag, "rebuildAllOnCpu failed", t)
        }
    }

    private fun runInference(interp: Interpreter, bitmap: Bitmap): FloatArray {
        val inputShape = interp.getInputTensor(0).shape()
        val h = inputShape.getOrNull(1) ?: 224
        val w = inputShape.getOrNull(2) ?: 224

        val bufferSize = 4 * w * h * 3
        val currentInput = inputBuffer.takeIf { it?.capacity() == bufferSize }
            ?: ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder()).also { inputBuffer = it }

        val pixelCount = w * h
        val currentPixels = pixelsArray.takeIf { it?.size == pixelCount }
            ?: IntArray(pixelCount).also { pixelsArray = it }

        currentInput.rewind()

        val resized = if (bitmap.width != w || bitmap.height != h) {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        resized.getPixels(currentPixels, 0, w, 0, 0, w, h)

        val inv255 = 1.0f / 255.0f
        for (p in currentPixels) {
            currentInput.putFloat(((p shr 16) and 0xFF) * inv255)
            currentInput.putFloat(((p shr 8) and 0xFF) * inv255)
            currentInput.putFloat((p and 0xFF) * inv255)
        }
        currentInput.rewind()

        if (resized !== bitmap) resized.recycle()

        val outShape = interp.getOutputTensor(0).shape()
        val outSize = outShape.last()
        val output = Array(1) { FloatArray(outSize) }
        interp.run(currentInput, output)
        return output[0]
    }

    companion object {
        @Volatile
        private var instance: GuardianAiDetector? = null

        fun get(context: Context): GuardianAiDetector =
            instance ?: synchronized(this) {
                instance ?: GuardianAiDetector(context.applicationContext)
                    .also { instance = it }
            }
    }
}
