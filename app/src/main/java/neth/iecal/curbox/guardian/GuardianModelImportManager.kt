package neth.iecal.curbox.guardian

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File

/** Import progress for the model-import settings screen. */
sealed class GuardianImportProgress {
    data object Idle : GuardianImportProgress()
    data class Working(val percent: Int) : GuardianImportProgress()
    data class Success(val modelName: String, val sizeBytes: Long) : GuardianImportProgress()
    data class Error(val modelName: String, val message: String) : GuardianImportProgress()
}

/** Where a model currently lives + whether it passes shape validation. */
data class GuardianModelStatus(
    val name: String,
    val source: String,        // "bundled" | "imported" | "missing"
    val sizeBytes: Long,
    val valid: Boolean,
)

/**
 * Copies a user-picked .tflite file into filesDir so GuardianAiDetector can load it.
 * Ported from Dogs-of-KAHAF `ModelImportManager`, adapted to Curbox style
 * (no Hilt — plain class with an app-scoped singleton holder).
 *
 * M5.5: import is now shape-validated — a wrong-format file is rejected
 * immediately instead of failing silently at scan time.
 */
class GuardianModelImportManager(private val context: Context) {

    private val _progress = MutableStateFlow<GuardianImportProgress>(GuardianImportProgress.Idle)
    val progress: StateFlow<GuardianImportProgress> = _progress.asStateFlow()

    private val tag = "GuardianModelImport"

    suspend fun importModel(uri: Uri, modelName: String): Result<File> =
        withContext(Dispatchers.IO) {
            _progress.value = GuardianImportProgress.Working(0)
            try {
                val finalFile = File(context.filesDir, modelName)
                val tmp = File(context.filesDir, "$modelName.tmp")

                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        val msg = "Cannot open file — try a different file manager"
                        _progress.value = GuardianImportProgress.Error(modelName, msg)
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        val max = GuardianConstants.MAX_MODEL_BYTES
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > max) {
                                tmp.delete()
                                val msg = "File too large (max 500 MB)"
                                _progress.value = GuardianImportProgress.Error(modelName, msg)
                                return@withContext Result.failure(IllegalStateException(msg))
                            }
                            out.write(buf, 0, n)
                            _progress.value = GuardianImportProgress.Working(
                                ((total * 100) / max).toInt().coerceAtMost(99)
                            )
                        }
                    }
                }

                if (tmp.length() < 1024) {
                    tmp.delete()
                    val msg = "File too small — not a valid model"
                    _progress.value = GuardianImportProgress.Error(modelName, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tmp.renameTo(finalFile)) {
                    tmp.copyTo(finalFile, overwrite = true)
                    tmp.delete()
                }

                // M5.5: validate the imported model's input/output shape so a
                // wrong-format file can't silently break detection later.
                val check = verifyFile(finalFile)
                if (check.isNotBlank()) {
                    finalFile.delete()
                    val msg = "Invalid model: $check"
                    _progress.value = GuardianImportProgress.Error(modelName, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                _progress.value = GuardianImportProgress.Success(modelName, finalFile.length())
                Result.success(finalFile)
            } catch (t: Throwable) {
                _progress.value =
                    GuardianImportProgress.Error(modelName, t.message ?: "error")
                Result.failure(t)
            }
        }

    fun isModelImported(modelName: String): Boolean {
        val f = File(context.filesDir, modelName)
        return f.exists() && f.length() > 0
    }

    fun modelSizeBytes(modelName: String): Long {
        val f = File(context.filesDir, modelName)
        return if (f.exists()) f.length() else 0L
    }

    /** Where the model lives: imported (filesDir) → bundled (assets) → missing. */
    fun modelStatus(modelName: String): GuardianModelStatus {
        val imported = File(context.filesDir, modelName)
        if (imported.exists() && imported.length() > 0) {
            return GuardianModelStatus(modelName, "imported", imported.length(), true)
        }
        val bundled = try {
            context.assets.openFd(modelName).use { fd -> fd.length }
        } catch (_: Throwable) { -1L }
        if (bundled > 0) {
            return GuardianModelStatus(modelName, "bundled", bundled, true)
        }
        return GuardianModelStatus(modelName, "missing", 0L, false)
    }

    /**
     * Loads a .tflite file and checks the input/output contract the detector
     * expects: input [1,224,224,3] float32, output [1,N] float32.
     * Returns "" when valid, otherwise a human-readable reason.
     */
    private fun verifyFile(file: File): String {
        var interp: Interpreter? = null
        return try {
            interp = Interpreter(file)
            val input = interp.getInputTensor(0)
            val output = interp.getOutputTensor(0)
            val inShape = input.shape()?.toList()
            val outShape = output.shape()?.toList()
            if (inShape == null || inShape.size != 4 ||
                inShape[1] != 224 || inShape[2] != 224 || inShape[3] != 3
            ) {
                "input must be [1,224,224,3] float32, got ${inShape ?: "?"}"
            } else if (outShape == null || outShape.isEmpty()) {
                "no output tensor"
            } else {
                ""
            }
        } catch (t: Throwable) {
            "could not parse file (${t.message ?: "unknown"})"
        } finally {
            try { interp?.close() } catch (_: Throwable) {}
        }
    }

    fun deleteModel(modelName: String): Boolean {
        val f = File(context.filesDir, modelName)
        return if (f.exists()) f.delete() else false
    }

    companion object {
        @Volatile
        private var instance: GuardianModelImportManager? = null

        fun get(context: Context): GuardianModelImportManager =
            instance ?: synchronized(this) {
                instance ?: GuardianModelImportManager(context.applicationContext)
                    .also { instance = it }
            }
    }
}
