package com.nabil.localtranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val ACTION_CHUNK = "com.nabil.localtranslator.CHUNK"
        const val EXTRA_PATH = "path"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "resultData"
        const val SAMPLE_RATE = 48000
        const val CHUNK_MILLIS = 2500
    }

    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification("جارٍ التقاط صوت YouTube محلياً"))
        if (running.get()) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        val data: Intent = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        } ?: return START_NOT_STICKY

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val mp = projection ?: return

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val captureCfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 4, SAMPLE_RATE * 2))
            .setAudioPlaybackCaptureConfig(captureCfg)
            .build()

        running.set(true)
        scope.launch {
            val r = record ?: return@launch
            r.startRecording()
            val buf = ByteArray(8192)
            var chunk = ByteArrayOutputStream()
            val targetBytes = (SAMPLE_RATE * 2L * CHUNK_MILLIS / 1000L).toInt()

            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) chunk.write(buf, 0, n)

                if (chunk.size() >= targetBytes) {
                    val bytes = chunk.toByteArray()
                    chunk = ByteArrayOutputStream()
                    val file = File(cacheDir, "cap_${System.currentTimeMillis()}.wav")
                    WavWriter.writePcm16Mono(file, bytes, SAMPLE_RATE)

                    sendBroadcast(Intent(ACTION_CHUNK).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PATH, file.absolutePath)
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        projection?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                "capture",
                "Local YouTube translation",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("مترجم YouTube المحلي")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
