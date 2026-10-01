package com.nabil.localtranslator

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nabil.localtranslator.databinding.ActivityMainBinding
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var whisperModel: WhisperModel? = null
    private val localTranslator by lazy { LocalTranslator(this) }
    private var modelChoice = ModelManager.TINY
    private val queue = ArrayDeque<File>()
    private val queueLock = Any()
    private val workerRunning = AtomicBoolean(false)
    private var runningTranslation = false
    private var lastShown = ""

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestProjection()
            else b.status.text = "لازم تسمح بإذن تسجيل الصوت حتى نلتقط صوت الفيديو داخلياً."
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val service = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)

                runningTranslation = true
                b.startLocal.text = "إيقاف الترجمة"
                b.status.text = "الوضع المباشر شغّال • التأخير المستهدف حوالي 3–5 ثوانٍ"
            } else {
                b.status.text = "لم يتم السماح بالتقاط صوت التشغيل."
            }
        }

    private val chunkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = intent?.getStringExtra(CaptureService.EXTRA_PATH) ?: return
            enqueue(File(p))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        registerReceiverCompat()

        b.web.settings.javaScriptEnabled = true
        b.web.settings.domStorageEnabled = true
        b.web.settings.mediaPlaybackRequiresUserGesture = false
        b.web.webChromeClient = WebChromeClient()
        b.web.webViewClient = WebViewClient()

        val shared = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else ""
        if (shared.isNotBlank()) b.url.setText(extractUrl(shared))

        b.modelGroup.setOnCheckedChangeListener { _, checkedId ->
            modelChoice = if (checkedId == b.modelBase.id) ModelManager.BASE else ModelManager.TINY
            whisperModel?.let { Whisper.releaseModel(it) }
            whisperModel = null
            b.modelState.text = "الموديل المختار: ${modelChoice.name}"
        }

        b.play.setOnClickListener {
            val id = youtubeId(b.url.text.toString())
            if (id == null) {
                b.status.text = "الرابط غير صالح"
            } else {
                val html = """
                    <!doctype html>
                    <html>
                    <head>
                      <meta name='viewport' content='width=device-width,initial-scale=1'>
                      <style>
                        html,body{margin:0;background:#000;height:100%;overflow:hidden}
                        iframe{width:100%;height:100%;border:0}
                      </style>
                    </head>
                    <body>
                      <iframe
                        src='https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0'
                        allow='autoplay; encrypted-media; picture-in-picture'
                        allowfullscreen>
                      </iframe>
                    </body>
                    </html>
                """.trimIndent()

                b.web.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
                b.status.text = "الفيديو جاهز. اضغط ابدأ الترجمة المحلية."
            }
        }

        b.startLocal.setOnClickListener {
            if (runningTranslation) {
                stopLocalTranslation()
            } else {
                lifecycleScope.launch {
                    try {
                        ensureModel()
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            requestProjection()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } catch (e: Throwable) {
                        b.status.text = "خطأ: ${e.message}"
                    }
                }
            }
        }
    }

    private fun stopLocalTranslation() {
        runningTranslation = false
        stopService(Intent(this, CaptureService::class.java))

        synchronized(queueLock) {
            while (queue.isNotEmpty()) queue.removeFirst().delete()
        }

        b.startLocal.text = "ابدأ الترجمة"
        b.status.text = "تم إيقاف الترجمة المحلية"
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private suspend fun ensureModel() {
        if (whisperModel != null) return

        b.modelState.text = "جارٍ تجهيز ${modelChoice.name}..."
        val file = ModelManager.ensure(this, modelChoice) { done, total ->
            runOnUiThread {
                val pct = if (total > 0) (done * 100 / total) else 0
                b.modelState.text = "تنزيل Whisper: $pct%"
            }
        }

        b.modelState.text = "تحميل Whisper إلى الذاكرة..."
        whisperModel = Whisper.loadModel(this, file.absolutePath)
        b.modelState.text = "جاهز: ${modelChoice.name}"
    }

    private fun enqueue(file: File) {
        synchronized(queueLock) {
            while (queue.isNotEmpty()) {
                queue.removeFirst().delete()
            }
            queue.addLast(file)
        }
        startWorkerIfNeeded()
    }

    private fun startWorkerIfNeeded() {
        if (!workerRunning.compareAndSet(false, true)) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (runningTranslation || synchronized(queueLock) { queue.isNotEmpty() }) {
                    val file = synchronized(queueLock) {
                        if (queue.isNotEmpty()) queue.removeFirst() else null
                    }

                    if (file == null) {
                        delay(120)
                        continue
                    }

                    processChunk(file)
                }
            } finally {
                workerRunning.set(false)
                if (synchronized(queueLock) { queue.isNotEmpty() }) startWorkerIfNeeded()
            }
        }
    }

    private suspend fun processChunk(file: File) {
        try {
            val model = whisperModel ?: return
            val result = Whisper.transcribe(model, file.absolutePath, WhisperConfig())
            val original = result.text.trim()
            if (original.isBlank()) return

            val ar = localTranslator.toArabic(original).trim()
            if (ar.isBlank() || ar == lastShown) return
            lastShown = ar

            withContext(Dispatchers.Main) {
                b.caption.text = ar
                b.status.text = "Live • محلي بالكامل • بلا API • يلحق أحدث صوت"
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                b.status.text = "تعذّر التحويل المحلي: ${e.message}"
            }
        } finally {
            file.delete()
        }
    }

    private fun registerReceiverCompat() {
        val filter = IntentFilter(CaptureService.ACTION_CHUNK)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(chunkReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(chunkReceiver, filter)
        }
    }

    private fun extractUrl(s: String): String =
        Regex("""https?://\S+""").find(s)?.value ?: s

    private fun youtubeId(url: String): String? = try {
        val u = URI(extractUrl(url.trim()))
        when {
            u.host?.contains("youtu.be") == true ->
                u.path.trim('/').substringBefore('/')

            u.path?.startsWith("/shorts/") == true ->
                u.path.split("/").getOrNull(2)

            else ->
                u.query?.split("&")
                    ?.mapNotNull {
                        val p = it.split("=", limit = 2)
                        if (p.firstOrNull() == "v") p.getOrNull(1) else null
                    }
                    ?.firstOrNull()
        }
    } catch (_: Throwable) {
        null
    }

    override fun onDestroy() {
        try { unregisterReceiver(chunkReceiver) } catch (_: Throwable) {}
        localTranslator.close()
        whisperModel?.let { Whisper.releaseModel(it) }
        stopService(Intent(this, CaptureService::class.java))
        super.onDestroy()
    }
}
