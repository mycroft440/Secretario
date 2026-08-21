package com.callguard.ai.voip

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WavInfo(
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataLength: Long
) {
    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSecond > 0) dataLength * 1000L / bytesPerSecond else 0L
        }
}

object WavParser {
    fun read(file: File): WavInfo {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 44) { "WAV incompleto." }
            require(readAscii(input, 4) == "RIFF") { "Arquivo não é RIFF/WAV." }
            input.skipBytes(4)
            require(readAscii(input, 4) == "WAVE") { "Arquivo não é WAVE." }

            var channels = 0
            var sampleRate = 0
            var bits = 0
            var pcmFormat = 0
            var dataOffset = -1L
            var dataLength = -1L

            while (input.filePointer + 8 <= input.length()) {
                val id = readAscii(input, 4)
                val size = readLeInt(input).toLong() and 0xffffffffL
                val payload = input.filePointer
                when (id) {
                    "fmt " -> if (size >= 16) {
                        pcmFormat = readLeShort(input)
                        channels = readLeShort(input)
                        sampleRate = readLeInt(input)
                        input.skipBytes(6)
                        bits = readLeShort(input)
                    }
                    "data" -> {
                        dataOffset = payload
                        dataLength = minOf(size, input.length() - payload)
                        break
                    }
                }
                input.seek(payload + size + (size and 1L))
            }

            require(pcmFormat == 1) { "STT exige WAV PCM linear." }
            require(channels == 1) { "STT exige áudio mono." }
            require(bits == 16) { "STT exige PCM 16-bit." }
            require(sampleRate in 8_000..48_000) { "Sample rate WAV inválido." }
            require(dataOffset >= 0 && dataLength > 0) { "WAV sem chunk de áudio." }
            return WavInfo(channels, sampleRate, bits, dataOffset, dataLength)
        }
    }

    private fun readAscii(file: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        file.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLeShort(file: RandomAccessFile): Int {
        val b0 = file.readUnsignedByte()
        val b1 = file.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readLeInt(file: RandomAccessFile): Int {
        val b0 = file.readUnsignedByte()
        val b1 = file.readUnsignedByte()
        val b2 = file.readUnsignedByte()
        val b3 = file.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

object VoskPtModelManager {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
    private const val MAX_DOWNLOAD_BYTES = 90L * 1024L * 1024L

    fun modelDir(context: Context): File = File(context.filesDir, "models/vosk-pt-small-0.3")

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.isDirectory && File(dir, "am").isDirectory && File(dir, "conf").isDirectory
    }

    suspend fun install(context: Context, progress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
                val zip = File(context.cacheDir, "vosk-pt-small.zip.part")
                val unpack = File(modelsDir, "vosk-pt-small.unpack")
                val destination = modelDir(context)
                zip.delete()
                unpack.deleteRecursively()
                unpack.mkdirs()

                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CallGuardAI/0.1")
                }
                try {
                    connection.connect()
                    require(connection.responseCode in 200..299) {
                        "Download STT HTTP ${connection.responseCode}"
                    }
                    val expected = connection.contentLengthLong
                    require(expected <= 0 || expected <= MAX_DOWNLOAD_BYTES) { "Modelo STT excede limite." }
                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(zip).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                require(total <= MAX_DOWNLOAD_BYTES) { "Download STT excede limite." }
                                output.write(buffer, 0, read)
                                if (expected > 0) progress(((total * 100) / expected).toInt().coerceIn(0, 100))
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                ZipInputStream(zip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val relative = entry.name.substringAfter('/', entry.name).trimStart('/')
                        if (relative.isNotBlank()) {
                            val out = File(unpack, relative).canonicalFile
                            require(out.path.startsWith(unpack.canonicalPath + File.separator)) {
                                "ZIP STT contém caminho inseguro."
                            }
                            if (entry.isDirectory) out.mkdirs() else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                require(File(unpack, "am").isDirectory && File(unpack, "conf").isDirectory) {
                    "Estrutura do modelo Vosk inesperada."
                }

                val backup = File(modelsDir, "vosk-pt-small.backup")
                backup.deleteRecursively()
                if (destination.exists()) require(destination.renameTo(backup)) {
                    "Não foi possível preservar o STT anterior."
                }
                if (!unpack.renameTo(destination)) {
                    backup.renameTo(destination)
                    error("Falha ao ativar modelo STT.")
                }
                backup.deleteRecursively()
                zip.delete()
                progress(100)
                destination
            }.also {
                File(context.cacheDir, "vosk-pt-small.zip.part").delete()
            }
        }
}

class VoskOfflineTranscriber(private val context: Context) : AutoCloseable {
    private var model: Model? = null

    suspend fun transcribe(wav: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(VoskPtModelManager.isInstalled(context)) { "Modelo STT PT-BR não instalado." }
            val info = WavParser.read(wav)
            val activeModel = model ?: Model(VoskPtModelManager.modelDir(context).absolutePath).also { model = it }
            Recognizer(activeModel, info.sampleRate.toFloat()).use { recognizer ->
                RandomAccessFile(wav, "r").use { input ->
                    input.seek(info.dataOffset)
                    var remaining = info.dataLength
                    val buffer = ByteArray(16 * 1024)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count <= 0) break
                        recognizer.acceptWaveForm(buffer, count)
                        remaining -= count
                    }
                }
                JSONObject(recognizer.finalResult).optString("text").trim().take(1_500)
            }
        }
    }

    override fun close() {
        model?.close()
        model = null
    }
}

class OfflineAndroidTts(private val context: Context) : AutoCloseable {
    private var tts: TextToSpeech? = null

    suspend fun synthesize(text: String, output: File): Result<Long> = runCatching {
        val engine = ensureInitialized()
        output.parentFile?.mkdirs()
        output.delete()
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine<Unit> { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                }
                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("TTS offline falhou."))
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("TTS offline falhou: $errorCode"))
                    }
                }
            })
            val result = engine.synthesizeToFile(text.take(500), null, output, id)
            if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Não foi possível iniciar síntese TTS."))
            }
        }
        require(output.isFile && output.length() > 44) { "TTS não produziu WAV útil." }
        WavParser.read(output).durationMs.coerceAtLeast(300L)
    }

    private suspend fun ensureInitialized(): TextToSpeech {
        tts?.let { return it }
        val engine = suspendCancellableCoroutine<TextToSpeech> { continuation ->
            lateinit var instance: TextToSpeech
            instance = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    if (continuation.isActive) continuation.resume(instance)
                } else if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("TTS Android indisponível."))
                }
            }
        }
        val locale = Locale("pt", "BR")
        require(engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            engine.shutdown()
            "Voz PT-BR não instalada."
        }
        engine.language = locale
        val offline = engine.voices
            ?.filter { !it.isNetworkConnectionRequired && it.locale.language == "pt" }
            ?.sortedByDescending { it.locale.country == "BR" }
            ?.firstOrNull()
        requireNotNull(offline) {
            engine.shutdown()
            "Nenhuma voz PT-BR offline instalada no Android."
        }
        engine.voice = offline
        engine.setSpeechRate(1.0f)
        tts = engine
        return engine
    }

    override fun close() {
        tts?.shutdown()
        tts = null
    }
}
