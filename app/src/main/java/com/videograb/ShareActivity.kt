package com.videograb

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent?.getStringExtra(android.content.Intent.EXTRA_TEXT))
        if (url == null) {
            Toast.makeText(this, "مفيش رابط في اللي اتشارك", Toast.LENGTH_SHORT).show()
        } else {
            DownloadService.start(this, url)
            Toast.makeText(this, "بدأ التحميل… شوف الإشعارات", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

fun extractUrl(text: String?): String? =
    text?.let { Regex("""https?://\S+""").find(it)?.value }
