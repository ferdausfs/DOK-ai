package neth.iecal.curbox.guardian

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Import progress for the model-import settings screen. */
sealed class GuardianImportProgress {
    data object Idle : GuardianImportProgress()
    data class Working(val percent: Int) : GuardianImportProgress()
    data class Success(val modelName: String, val sizeBytes: Long) : GuardianImportProgress()
    data class Error(val modelName: String, val message: String) : GuardianImportProgress()
}

/**
 * Copies a user-picked .tflite file into filesDir so GuardianAiDetector can load it.
 * Ported from Dogs-of-KAHAF `ModelImportManager`, adapted to Curbox style
 * (no Hilt — plain class with an app-scoped singleton holder).
 */
class GuardianModelImportManager(private val context: Context) {

    private val _progress = MutableStateFlow<GuardianImportProgress>(GuardianImportProgress.Idle)
    val progress: StateFlow<GuardianImportProgress> = _progress.asStateFlow()

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
