package com.callguard.ai.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class InstalledModelInfo(
    val file: File,
    val bytes: Long,
    val sha256: String
)

object ModelInstaller {
    suspend fun installFromUri(context: Context, source: Uri): Result<InstalledModelInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "models").apply { mkdirs() }
                val destination = File(dir, FunctionGemmaTriageEngine.MODEL_FILE)
                val temp = File(dir, "${FunctionGemmaTriageEngine.MODEL_FILE}.part")

                if (temp.exists()) temp.delete()
                context.contentResolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "Não foi possível abrir o modelo" }
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                require(temp.length() > 1_000_000L) {
                    "Arquivo do modelo é pequeno demais para um .litertlm válido"
                }

                val digest = sha256(temp)
                if (destination.exists()) destination.delete()
                require(temp.renameTo(destination)) { "Falha ao finalizar instalação do modelo" }
                InstalledModelInfo(destination, destination.length(), digest)
            }
        }

    suspend fun remove(context: Context): Boolean = withContext(Dispatchers.IO) {
        File(context.filesDir, "models/${FunctionGemmaTriageEngine.MODEL_FILE}").delete()
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
