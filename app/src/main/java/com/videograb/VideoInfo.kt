package com.videograb

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.Locale

data class Option(
    val type: String,       // video | mp3_320 | mp3_128 | m4a | opus
    val height: Int,
    val sizeBytes: Long,
    val approx: Boolean,
    val parts: Int
)

data class VideoMeta(
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumb: String,
    val video: List<Option>,
    val audio: List<Option>
)

fun extractUrl(text: String?): String? =
    text?.let { Regex("""https?://\S+""").find(it)?.value }

fun fmtSize(bytes: Long, approx: Boolean): String {
    if (bytes <= 0) return L.t("Size unknown", "الحجم غير معروف")
    val mb = bytes / 1048576.0
    val s = if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
    else String.format(Locale.US, "%.1f MB", mb)
    return if (approx) "~$s" else s
}

fun fmtDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun cleanError(e: Throwable): String {
    val raw = e.message?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        ?: "Unknown error"
    return when {
        raw.contains("Sign in", true) || raw.contains("not a bot", true) ->
            L.t(
                "YouTube asked for sign-in. Tap 'Update engine' and try again.",
                "يوتيوب طلب تسجيل الدخول. اضغط تحديث المحرك وجرّب تاني."
            )
        raw.contains("Unsupported URL", true) ->
            L.t("This link isn't supported.", "الرابط ده مش مدعوم.")
        else -> raw.take(160)
    }
}

private fun JSONObject.str(name: String): String {
    val v = opt(name)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

private fun JSONObject.num(name: String): Double {
    val v = opt(name)
    return if (v is Number) v.toDouble() else 0.0
}

object InfoFetcher {

    private class F(
        val height: Int,
        val ext: String,
        val hasV: Boolean,
        val hasA: Boolean,
        val size: Long,
        val approx: Boolean
    )

    fun fetch(ctx: Context, url: String): VideoMeta {
        DownloadService.ensureInit(ctx)
        val id = "info_" + System.nanoTime()
        val req = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "15")
            addOption("--retries", "2")
        }
        // لو التحليل خد أكتر من 45 ثانية نقطعه
        val dog = Thread {
            try {
                Thread.sleep(45_000)
                YoutubeDL.getInstance().destroyProcessById(id)
            } catch (_: InterruptedException) {
            }
        }
        dog.isDaemon = true
        dog.start()
        val out = try {
            YoutubeDL.getInstance().execute(req, id) { _, _, _ -> }.out
        } finally {
            dog.interrupt()
        }
        return parse(out)
    }

    private fun parse(out: String): VideoMeta {
        val start = out.indexOf('{')
        if (start < 0) error(L.t("No info returned", "مفيش بيانات رجعت"))
        val j = JSONObject(out.substring(start))

        val title = j.str("title").ifEmpty { "Video" }
        val uploader = j.str("uploader").ifEmpty { j.str("channel") }
        val duration = j.num("duration")
        val thumb = j.str("thumbnail").replace("http://", "https://")

        val list = ArrayList<F>()
        val arr = j.optJSONArray("formats")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val h = o.num("height").toInt()
                val vc = o.str("vcodec")
                val ac = o.str("acodec")
                val hasV = vc.isNotEmpty() && vc != "none" && h > 0
                val hasA = ac.isNotEmpty() && ac != "none"
                if (!hasV && !hasA) continue
                var size = o.num("filesize").toLong()
                var approx = false
                if (size <= 0) {
                    size = o.num("filesize_approx").toLong()
                    approx = true
                }
                if (size <= 0) {
                    val tbr = o.num("tbr")
                    if (tbr > 0 && duration > 0) size = (tbr * 125 * duration).toLong()
                    approx = true
                }
                if (size <= 0) size = -1
                list.add(F(h, o.str("ext"), hasV, hasA, size, approx))
            }
        }

        val audioOnly = list.filter { it.hasA && !it.hasV }
        val bestAudio = audioOnly.filter { it.ext == "m4a" }.maxByOrNull { it.size }
            ?: audioOnly.maxByOrNull { it.size }

        val video = ArrayList<Option>()
        val heights = list.filter { it.hasV }.map { it.height }.distinct()
            .sortedDescending().take(7)
        for (h in heights) {
            val vOnly = list.filter { it.hasV && !it.hasA && it.height == h }
            val comb = list.filter { it.hasV && it.hasA && it.height == h }
            if (vOnly.isNotEmpty()) {
                val v = vOnly.filter { it.ext == "mp4" }.maxByOrNull { it.size }
                    ?: vOnly.maxByOrNull { it.size }!!
                val a = bestAudio
                val total = if (v.size > 0 && (a == null || a.size > 0)) v.size + (a?.size ?: 0L) else -1L
                video.add(
                    Option("video", h, total, v.approx || (a?.approx ?: false), if (a != null) 2 else 1)
                )
            } else if (comb.isNotEmpty()) {
                val c = comb.maxByOrNull { it.size }!!
                video.add(Option("video", h, c.size, c.approx, 1))
            }
        }

        fun conv(kbps: Int): Long = if (duration > 0) (duration * kbps * 125).toLong() else -1L
        val m4a = audioOnly.filter { it.ext == "m4a" }.maxByOrNull { it.size }
        val webm = audioOnly.filter { it.ext == "webm" || it.ext == "opus" }.maxByOrNull { it.size }

        val audio = listOf(
            Option("mp3_320", 0, conv(320), true, 1),
            Option("mp3_128", 0, conv(128), true, 1),
            Option("m4a", 0, m4a?.size?.takeIf { it > 0 } ?: conv(128), m4a == null || m4a.approx, 1),
            Option("opus", 0, webm?.size?.takeIf { it > 0 } ?: conv(128), webm == null || webm.approx, 1)
        )

        return VideoMeta(title, uploader, duration.toLong(), thumb, video, audio)
    }
}
