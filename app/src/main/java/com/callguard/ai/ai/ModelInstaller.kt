package com.callguard.ai.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class InstalledModelInfo(
    val file: File,
    val bytes: Long,
    val sha256: String
)

object ModelInstaller {
    private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    private const val MAX_MODEL_BYTES = 1_500L * 1024L * 1024L

    suspend fun installFromUri(context: Context, source: Uri): Result<InstalledModelInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                validateDisplayName(context, source)

                val dir = File(context.filesDir, "models").apply {
                    require(exists() || mkdirs()) { "Não foi possível criar diretório de modelos" }
                }
                val destination = File(dir, FunctionGemmaTriageEngine.MODEL_FILE)
                val temp = File(dir, "${FunctionGemmaTriageEngine.MODEL_FILE}.part")
                val backup = File(dir, "${FunctionGemmaTriageEngine.MODEL_FILE}.bak")

                temp.delete()
                backup.delete()

                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "Não foi possível abrir o modelo" }
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= MAX_MODEL_BYTES) { "Modelo excede o limite de 1,5 GB" }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }

                require(temp.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
                    "Tamanho incompatível com o modelo offline esperado"
                }
                val digest = sha256(temp)

                // Keep the previous working model until the new file is safely in place.
                if (destination.exists()) {
                    require(destination.renameTo(backup)) { "Não foi possível preservar o modelo anterior" }
                }
                val installed = temp.renameTo(destination)
                if (!installed) {
                    if (backup.exists()) backup.renameTo(destination)
                    error("Falha ao finalizar instalação do modelo")
                }
                backup.delete()

                InstalledModelInfo(destination, destination.length(), digest)
            }.also {
                File(context.filesDir, "models/${FunctionGemmaTriageEngine.MODEL_FILE}.part").delete()
            }
        }

    suspend fun remove(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models")
        File(dir, FunctionGemmaTriageEngine.MODEL_FILE).delete().also {
            File(dir, "${FunctionGemmaTriageEngine.MODEL_FILE}.part").delete()
            File(dir, "${FunctionGemmaTriageEngine.MODEL_FILE}.bak").delete()
        }
    }

    private fun validateDisplayName(context: Context, source: Uri) {
        val name = context.contentResolver.query(
            source,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (!name.isNullOrBlank()) {
            require(name.endsWith(".litertlm", ignoreCase = true)) {
                "Selecione um arquivo .litertlm"
            }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
