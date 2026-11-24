package com.videograb

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object Status {
    val text = MutableStateFlow("")
    val progress = MutableStateFlow(-1) // -1 مخفي، -2 غير محدد، 0..100 نسبة

    fun set(t: String, p: Int = -1) {
        text.value = t
        progress.value = p
    }
}

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private val cancelled = ConcurrentHashMap.newKeySet<Int>()
    private val stalled = ConcurrentHashMap.newKeySet<Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.load(applicationContext)
        createChannel()
        startForeground(FG_ID, baseNotification(L.t("VideoGrab is running", "VideoGrab شغال")).build())

        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            cancelled.add(id)
            YoutubeDL.getInstance().destroyProcessById("dl_$id")
            if (active.get() == 0) stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            if (active.get() == 0) stopSelf()
            return START_NOT_STICKY
        }
        val opt = Option(
            type = intent.getStringExtra(EXTRA_TYPE) ?: "video",
            height = intent.getIntExtra(EXTRA_HEIGHT, 1080),
            sizeBytes = -1,
            approx = true,
            parts = intent.getIntExtra(EXTRA_PARTS, 1)
        )
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"

        val notifId = 100 + startId
        active.incrementAndGet()
        scope.launch {
            val beat = AtomicLong(System.currentTimeMillis())
            val dog = launch {
                while (isActive) {
                    delay(5_000)
                    if (System.currentTimeMillis() - beat.get() > STALL_MS) {
                        stalled.add(notifId)
                        YoutubeDL.getInstance().destroyProcessById("dl_$notifId")
                        break
                    }
                }
            }
            try {
                download(url, notifId, opt, title, beat)
            } catch (e: Exception) {
                val msg: String? = when {
                    cancelled.remove(notifId) -> null
                    stalled.remove(notifId) -> L.t(
                        "Download stalled (no data for 2 minutes). Check your connection and retry.",
                        "التحميل وقف (مفيش بيانات من دقيقتين). اتأكد من النت وجرّب تاني."
                    )
                    else -> cleanError(e)
                }
                if (msg == null) {
                    Status.set(L.t("Cancelled", "تم الإلغاء"))
                    getSystemService(NotificationManager::class.java).cancel(notifId)
                } else {
                    Status.set(msg)
                    notify(
                        notifId,
                        baseNotification(L.t("Download failed", "فشل التحميل"))
                            .setContentText(msg).setAutoCancel(true).build()
                    )
                }
            } finally {
                dog.cancel()
                File(cacheDir, "dl_$notifId").deleteRecursively()
                if (active.decrementAndGet() == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun download(url: String, notifId: Int, opt: Option, title: String, beat: AtomicLong) {
        ensureInit(applicationContext)

        val tmp = File(cacheDir, "dl_$notifId").apply { deleteRecursively(); mkdirs() }
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-N", "8")
            addOption("--socket-timeout", "20")
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
            addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
            when (opt.type) {
                "mp3_320" -> {
                    addOption("-f", "ba/b"); addOption("-x")
                    addOption("--audio-format", "mp3"); addOption("--audio-quality", "320K")
                }
                "mp3_128" -> {
                    addOption("-f", "ba/b"); addOption("-x")
                    addOption("--audio-format", "mp3"); addOption("--audio-quality", "128K")
                }
                "m4a" -> {
                    addOption("-f", "ba[ext=m4a]/ba/b"); addOption("-x")
                    addOption("--audio-format", "m4a")
                }
                "opus" -> {
                    addOption("-f", "ba[ext=webm]/ba/b"); addOption("-x")
                    addOption("--audio-format", "opus")
                }
                else -> {
                    val h = opt.height
                    addOption(
                        "-f",
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b"
                    )
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

        Status.set(L.t("Starting download…", "جاري بدء التحميل…"), -2)
        val parts = opt.parts.coerceAtLeast(1)
        var part = 1
        var sawDest = false
        var lastRaw = 0f
        var lastShown = -1

        YoutubeDL.getInstance().execute(request, "dl_$notifId") { progress, _, line ->
            beat.set(System.currentTimeMillis())
            if (line.contains("[download] Destination:")) {
                if (sawDest) part++ else sawDest = true
                lastRaw = 0f
            }
            if (progress < 0) return@execute
            if (progress < lastRaw - 30f) part++
            lastRaw = progress
            val p = minOf(part, parts)
            val overall = (((p - 1) + progress / 100f) / parts * 100f).toInt().coerceIn(0, 100)
            if (overall != lastShown) {
                lastShown = overall
                Status.set(L.t("Downloading… $overall%", "جاري التحميل… $overall%"), overall)
                notify(
                    notifId,
                    baseNotification(title)
                        .setContentText(L.t("Downloading… $overall%", "جاري التحميل… $overall%"))
                        .setProgress(100, overall, false)
                        .setOngoing(true)
                        .addAction(cancelAction(notifId))
                        .build()
                )
            }
        }
        beat.set(System.currentTimeMillis())

        val file = tmp.listFiles()
            ?.filter { it.extension.lowercase() in MEDIA_EXT }
            ?.maxByOrNull { it.length() }
            ?: error(L.t("File not found after download", "الملف مش موجود بعد التحميل"))

        val isAudio = file.extension.lowercase() in AUDIO_EXT
        val uri = saveToGallery(file, isAudio)
        file.delete()

        Status.set(L.t("Saved: ${file.name}", "تم الحفظ: ${file.name}"))
        val open = PendingIntent.getActivity(
            this, notifId,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (isAudio) "audio/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            notifId,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(L.t("Download complete ✅", "تم التحميل ✅"))
                .setContentText(file.name)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun saveToGallery(file: File, isAudio: Boolean): Uri {
        val mime = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val collection = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val folder = if (isAudio) "Music/VideoGrab" else "Movies/VideoGrab"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(collection, values) ?: error("Could not create file")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun cancelAction(id: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, id,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, L.t("Cancel", "إلغاء"), pi
        )
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
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val FG_ID = 1
        private const val STALL_MS = 120_000L
        private const val ACTION_CANCEL = "com.videograb.CANCEL"
        private const val EXTRA_ID = "id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_PARTS = "parts"
        private const val EXTRA_TITLE = "title"
        private val AUDIO_EXT = setOf("mp3", "m4a", "opus", "ogg")
        private val MEDIA_EXT = AUDIO_EXT + setOf("mp4", "mkv", "webm", "mov")
        @Volatile private var initialized = false

        fun start(context: Context, url: String, opt: Option, title: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_TYPE, opt.type)
                    .putExtra(EXTRA_HEIGHT, opt.height)
                    .putExtra(EXTRA_PARTS, opt.parts)
                    .putExtra(EXTRA_TITLE, title)
            )
        }

        @Synchronized
        fun ensureInit(context: Context) {
            if (initialized) return
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized = true
        }
    }
}
