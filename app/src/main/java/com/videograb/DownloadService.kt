package com.videograb

import com.yausername.aria2c.Aria2c
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object Status {
    val text = MutableStateFlow("جاهز. شارك رابط فيديو للتطبيق أو الصقه هنا.")
}

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(FG_ID, baseNotification("VideoGrab شغال في الخلفية").build())

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (active.get() == 0) stopSelf()
            return START_NOT_STICKY
        }

        val notifId = 100 + startId
        active.incrementAndGet()
        scope.launch {
            try {
                download(url, notifId)
            } catch (e: Exception) {
                val msg = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: "خطأ غير معروف"
                Status.text.value = "فشل التحميل: $msg"
                notify(notifId, baseNotification("فشل التحميل").setContentText(msg).setAutoCancel(true).build())
            } finally {
                if (active.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun download(url: String, notifId: Int) {
        ensureInit(applicationContext)

        val tmp = File(cacheDir, "dl_$notifId").apply { deleteRecursively(); mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("-f", "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--downloader", "libaria2c.so")
addOption("--external-downloader-args", "aria2c:-x 16 -s 16 -k 1M")
            addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
        }

        Status.text.value = "جاري التحميل…"
        var lastPct = -1
        YoutubeDL.getInstance().execute(request, "dl_$notifId") { progress, _, _ ->
            val pct = progress.toInt()
            if (pct != lastPct && pct in 0..100) {
                lastPct = pct
                Status.text.value = "جاري التحميل… $pct%"
                notify(
                    notifId,
                    baseNotification("جاري التحميل… $pct%")
                        .setProgress(100, pct, false).setOngoing(true).build()
                )
            }
        }

        val file = tmp.listFiles()
            ?.filter { it.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov") }
            ?.maxByOrNull { it.length() }
            ?: error("ملقاش الملف بعد التحميل")

        val uri = saveToGallery(file)
        file.delete(); tmp.deleteRecursively()

        Status.text.value = "تم التحميل: ${file.name}"
        val open = PendingIntent.getActivity(
            this, notifId,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            notifId,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("تم التحميل ✅")
                .setContentText(file.name)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun saveToGallery(file: File): Uri {
        val mime = when (file.extension.lowercase()) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoGrab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("مقدرتش أنشئ الملف في المعرض")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun baseNotification(title: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)

    private fun notify(id: Int, n: Notification) =
        getSystemService(NotificationManager::class.java).notify(id, n)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "التحميلات", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val FG_ID = 1
        private const val EXTRA_URL = "url"
        @Volatile private var initialized = false

        fun start(context: Context, url: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            )
        }

        @Synchronized
        fun ensureInit(context: Context) {
            if (initialized) return
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            Aria2c.getInstance().init(context)
            initialized = true
        }
    }
}
