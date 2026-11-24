package com.videograb

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "VideoGrab"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "من يوتيوب أو فيسبوك أو انستجرام أو تيك توك: اضغط شير ← VideoGrab، أو الصق الرابط هنا."
            setPadding(0, pad, 0, pad)
        }
        val input = EditText(this).apply {
            this.hint = "الصق رابط الفيديو"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val go = Button(this).apply { text = "تحميل" }
        val update = Button(this).apply { text = "تحديث محرك التحميل (لو موقع وقف)" }
        val status = TextView(this).apply { setPadding(0, pad, 0, 0) }

        root.addView(title); root.addView(hint); root.addView(input)
        root.addView(go); root.addView(update); root.addView(status)
        setContentView(root)

        go.setOnClickListener {
            val url = extractUrl(input.text?.toString())
            if (url == null) {
                Toast.makeText(this, "الصق رابط صحيح", Toast.LENGTH_SHORT).show()
            } else {
                chooseQualityAndDownload(this, url)
                input.setText("")
            }
        }

        update.setOnClickListener {
            Status.text.value = "جاري تحديث المحرك…"
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        DownloadService.ensureInit(applicationContext)
                        YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                        "تم تحديث المحرك"
                    } catch (e: Exception) {
                        "فشل التحديث: ${e.message}"
                    }
                }
                Status.text.value = msg
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Status.text.collect { status.text = it }
            }
        }
    }
}
