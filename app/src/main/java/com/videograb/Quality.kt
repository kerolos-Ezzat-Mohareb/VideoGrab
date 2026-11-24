package com.videograb

import android.app.Activity
import android.app.AlertDialog

enum class Quality(val label: String, val audioOnly: Boolean, val height: Int) {
    BEST("أعلى جودة (حتى 1080p)", false, 1080),
    Q720("720p", false, 720),
    Q480("480p", false, 480),
    Q360("360p", false, 360),
    AUDIO("صوت فقط (MP3)", true, 0)
}

fun chooseQualityAndDownload(activity: Activity, url: String, onDone: () -> Unit = {}) {
    val items = Quality.values()
    AlertDialog.Builder(activity)
        .setTitle("اختار الجودة")
        .setItems(items.map { it.label }.toTypedArray()) { _, i ->
            DownloadService.start(activity, url, items[i])
        }
        .setOnDismissListener { onDone() }
        .show()
}
