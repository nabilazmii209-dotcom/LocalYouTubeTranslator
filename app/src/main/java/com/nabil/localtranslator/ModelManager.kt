package com.nabil.localtranslator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    data class ModelChoice(val name: String, val file: String, val url: String)

    val TINY = ModelChoice(
        "Whisper Tiny متعدد اللغات (~75MB) • مباشر",
        "ggml-tiny.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    )

    val BASE = ModelChoice(
        "Whisper Base متعدد اللغات (~142MB) • أدق",
        "ggml-base.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    )

    fun modelFile(context: Context, model: ModelChoice): File =
        File(context.getExternalFilesDir("models"), model.file)

    suspend fun ensure(
        context: Context,
        model: ModelChoice,
        progress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val out = modelFile(context, model)
        if (out.exists() && out.length() > 50_000_000) return@withContext out
        out.parentFile?.mkdirs()

        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) error("Download HTTP ${conn.responseCode}")

        val total = conn.contentLengthLong
        val tmp = File(out.absolutePath + ".part")
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1024 * 256)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }

        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) error("تعذر حفظ موديل Whisper")
        out
    }
}
