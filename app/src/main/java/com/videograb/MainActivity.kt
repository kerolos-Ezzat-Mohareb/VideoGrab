package com.videograb

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        var cachedUrl: String? = null
        var cachedMeta: VideoMeta? = null
    }

    private var bgC = 0
    private var cardC = 0
    private var textC = 0
    private var mutedC = 0
    private var accentC = 0
    private var strokeC = 0

    private lateinit var input: EditText
    private lateinit var resultBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var bar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var fetchJob: Job? = null
    private var currentUrl: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) {
            bgC = 0xFF0B1020.toInt(); cardC = 0xFF151B2E.toInt(); textC = 0xFFF1F5F9.toInt()
            mutedC = 0xFF94A3B8.toInt(); accentC = 0xFF6366F1.toInt(); strokeC = 0xFF26304A.toInt()
        } else {
            bgC = 0xFFF8FAFC.toInt(); cardC = 0xFFFFFFFF.toInt(); textC = 0xFF0F172A.toInt()
            mutedC = 0xFF64748B.toInt(); accentC = 0xFF4F46E5.toInt(); strokeC = 0xFFE2E8F0.toInt()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        window.statusBarColor = bgC
        window.navigationBarColor = bgC
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(tv("VideoGrab", 26f, textC, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val lang = tv(if (L.ar) "English" else "العربية", 13f, accentC, true).apply {
            background = shape(cardC, 20, strokeC)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                L.save(this@MainActivity, !L.ar)
                recreate()
            }
        }
        header.addView(lang)
        root.addView(header, lp())

        root.addView(
            tv(
                L.t("Download videos and audio from anywhere", "نزّل الفيديوهات والصوت من أي مكان"),
                14f, mutedC
            ), lp(4)
        )

        // Input row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = L.t("Paste video link", "الصق رابط الفيديو")
            setHintTextColor(mutedC)
            setTextColor(textC)
            textSize = 15f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = shape(cardC, 16, strokeC)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val paste = tv(L.t("Paste", "لصق"), 14f, Color.WHITE, true).apply {
            background = shape(accentC, 16)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { pasteFromClipboard() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(paste, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        root.addView(row, lp(24))

        // Status card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardC, 16, strokeC)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusText = tv("", 14f, mutedC)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accentC)
            indeterminateTintList = ColorStateList.valueOf(accentC)
            visibility = View.GONE
        }
        statusCard.addView(statusText, lp())
        statusCard.addView(bar, lp(10))
        root.addView(statusCard, lp(12))

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox, lp())

        // Update engine
        val update = tv(L.t("Update download engine", "تحديث محرك التحميل"), 14f, accentC, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { updateEngine() }
        }
        root.addView(update, lp(24))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgC)
            isFillViewport = true
            addView(root)
        })

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val u = extractUrl(s?.toString())
                if (u != null && u != currentUrl) {
                    val r = Runnable { fetch(u) }
                    pending = r
                    handler.postDelayed(r, 600)
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Status.text.collect {
                        statusText.text = it.ifEmpty {
                            L.t(
                                "Paste a video link or share it from any app.",
                                "الصق رابط فيديو أو شاركه من أي تطبيق."
                            )
                        }
                    }
                }
                launch {
                    Status.progress.collect { p ->
                        when (p) {
                            -1 -> bar.visibility = View.GONE
                            -2 -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = true
                            }
                            else -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = false
                                bar.progress = p
                            }
                        }
                    }
                }
            }
        }

        val meta = cachedMeta
        if (meta != null) {
            currentUrl = cachedUrl
            input.setText(cachedUrl ?: "")
            render(meta)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val u = extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))
            i.action = null
            if (u != null) {
                fetch(u)
                input.setText(u)
            } else {
                Toast.makeText(this, L.t("No link found", "مفيش رابط"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
        val u = extractUrl(t)
        if (u == null) {
            Toast.makeText(this, L.t("No link in clipboard", "مفيش رابط في الحافظة"), Toast.LENGTH_SHORT).show()
        } else {
            fetch(u)
            input.setText(u)
        }
    }

    private fun fetch(url: String) {
        pending?.let { handler.removeCallbacks(it) }
        fetchJob?.cancel()
        currentUrl = url
        resultBox.removeAllViews()
        Status.set(L.t("Analyzing link…", "جاري تحليل الرابط…"), -2)
        fetchJob = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { InfoFetcher.fetch(applicationContext, url) }
            }
            r.onSuccess { meta ->
                cachedUrl = url
                cachedMeta = meta
                render(meta)
                Status.set(L.t("Choose a quality to download", "اختر الجودة للتحميل"))
            }.onFailure { e ->
                Status.set(cleanError(e))
            }
        }
    }

    private fun render(meta: VideoMeta) {
        resultBox.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardC, 20, strokeC)
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        if (meta.thumb.isNotEmpty()) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(strokeC, 14)
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(190)))
            loadThumb(meta.thumb, img)
        }
        card.addView(tv(meta.title, 16f, textC, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(12))
        val sub = listOf(meta.uploader, fmtDuration(meta.duration))
            .filter { it.isNotEmpty() }.joinToString("  •  ")
        if (sub.isNotEmpty()) card.addView(tv(sub, 13f, mutedC), lp(4))
        resultBox.addView(card, lp(16))

        if (meta.video.isNotEmpty()) {
            resultBox.addView(tv(L.t("Video", "فيديو"), 13f, mutedC, true), lp(20))
            for (o in meta.video) {
                resultBox.addView(
                    optionRow("${o.height}p", "MP4", fmtSize(o.sizeBytes, o.approx)) {
                        startDownload(meta, o)
                    }, lp(8)
                )
            }
        }
        resultBox.addView(tv(L.t("Audio", "صوت"), 13f, mutedC, true), lp(20))
        for (o in meta.audio) {
            val (title, sub2) = when (o.type) {
                "mp3_320" -> "MP3 · 320 kbps" to L.t("Converted · plays everywhere", "محوّل · يشتغل على كل الأجهزة")
                "mp3_128" -> "MP3 · 128 kbps" to L.t("Smaller file", "حجم أصغر")
                "m4a" -> "M4A · AAC" to L.t("Original quality, no re-encode", "الجودة الأصلية بدون تحويل")
                else -> "OPUS" to L.t("Best quality for its size", "أفضل جودة بالنسبة للحجم")
            }
            resultBox.addView(
                optionRow(title, sub2, fmtSize(o.sizeBytes, o.approx)) { startDownload(meta, o) },
                lp(8)
            )
        }
    }

    private fun optionRow(title: String, sub: String, size: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(cardC, 16, strokeC)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 16f, textC, true))
        texts.addView(tv(sub, 12f, mutedC))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tv(size, 14f, accentC, true))
        return row
    }

    private fun startDownload(meta: VideoMeta, opt: Option) {
        val url = currentUrl ?: return
        DownloadService.start(this, url, opt, meta.title)
        Toast.makeText(this, L.t("Download started", "بدأ التحميل"), Toast.LENGTH_SHORT).show()
    }

    private fun loadThumb(url: String, img: ImageView) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) img.setImageBitmap(bmp) else img.visibility = View.GONE
        }
    }

    private fun updateEngine() {
        Status.set(L.t("Updating engine…", "جاري تحديث المحرك…"), -2)
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    DownloadService.ensureInit(applicationContext)
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    L.t("Engine updated", "تم تحديث المحرك")
                } catch (e: Exception) {
                    L.t("Update failed: ", "فشل التحديث: ") + (e.message ?: "")
                }
            }
            Status.set(msg)
        }
    }
} كل موقع وحقوق أصحاب المحتوى."
                ), 12f, mutedC
            ), lp(20)
        )

        // ---------- Layout + tabs ----------
        scrolls = listOf(page(home), page(dl), page(about))
        val frame = FrameLayout(this)
        scrolls.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        val labels = listOf(
            L.t("Download", "تحميل"),
            L.t("Downloaded", "التنزيلات"),
            L.t("About", "حول")
        )
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardC)
        }
        tabViews = labels.mapIndexed { i, label ->
            tv(label, 13f, mutedC, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener { selectTab(i) }
            }
        }
        tabViews.forEach { bottomBar.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgC)
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        outer.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        outer.addView(View(this).apply { setBackgroundColor(strokeC) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        outer.addView(bottomBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(outer)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val u = extractUrl(s?.toString())
                if (u != null && u != currentUrl) {
                    val r = Runnable { fetch(u) }
                    pending = r
                    handler.postDelayed(r, 600)
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Status.text.collect {
                        statusText.text = it.ifEmpty {
                            L.t(
                                "Paste a video link or share it from any app.",
                                "الصق رابط فيديو أو شاركه من أي تطبيق."
                            )
                        }
                    }
                }
                launch {
                    Status.progress.collect { p ->
                        when (p) {
                            -1 -> bar.visibility = View.GONE
                            -2 -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = true
                            }
                            else -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = false
                                bar.progress = p
                            }
                        }
                    }
                }
                launch {
                    History.version.collect { if (lastTab == 1) renderDownloaded() }
                }
            }
        }

        val meta = cachedMeta
        if (meta != null) {
            currentUrl = cachedUrl
            input.setText(cachedUrl ?: "")
            render(meta)
        }
        selectTab(lastTab)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun selectTab(i: Int) {
        lastTab = i
        scrolls.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { idx, t -> t.setTextColor(if (idx == i) accentC else mutedC) }
        if (i == 1) renderDownloaded()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val u = extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))
            i.action = null
            selectTab(0)
            if (u != null) {
                fetch(u)
                input.setText(u)
            } else {
                toast(L.t("No link found", "مفيش رابط"))
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/KeroEzzat01")))
        } catch (e: Exception) {
            toast(L.t("Couldn't open the link", "مقدرتش أفتح اللينك"))
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
        val u = extractUrl(t)
        if (u == null) {
            toast(L.t("No link in clipboard", "مفيش رابط في الحافظة"))
        } else {
            fetch(u)
            input.setText(u)
        }
    }

    private fun fetch(url: String) {
        pending?.let { handler.removeCallbacks(it) }
        fetchJob?.cancel()
        currentUrl = url
        resultBox.removeAllViews()
        Status.set(L.t("Analyzing link…", "جاري تحليل الرابط…"), -2)
        fetchJob = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { InfoFetcher.fetch(applicationContext, url) }
            }
            r.onSuccess { meta ->
                cachedUrl = url
                cachedMeta = meta
                render(meta)
                Status.set(L.t("Choose a quality to download", "اختر الجودة للتحميل"))
            }.onFailure { e ->
                Status.set(cleanError(e))
            }
        }
    }

    private fun render(meta: VideoMeta) {
        resultBox.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardC, 20, strokeC)
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        if (meta.thumb.isNotEmpty()) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(strokeC, 14)
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(190)))
            loadThumb(meta.thumb, img)
        }
        card.addView(tv(meta.title, 16f, textC, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(12))
        val sub = listOf(meta.uploader, fmtDuration(meta.duration))
            .filter { it.isNotEmpty() }.joinToString("  •  ")
        if (sub.isNotEmpty()) card.addView(tv(sub, 13f, mutedC), lp(4))
        resultBox.addView(card, lp(16))

        if (meta.video.isNotEmpty()) {
            resultBox.addView(tv(L.t("Video", "فيديو"), 13f, mutedC, true), lp(20))
            for (o in meta.video) {
                resultBox.addView(
                    optionRow("${o.height}p", "MP4", fmtSize(o.sizeBytes, o.approx)) {
                        startDownload(meta, o)
                    }, lp(8)
                )
            }
        }
        resultBox.addView(tv(L.t("Audio", "صوت"), 13f, mutedC, true), lp(20))
        for (o in meta.audio) {
            val (title, sub2) = when (o.type) {
                "mp3_320" -> "MP3 · 320 kbps" to L.t("Converted · plays everywhere", "محوّل · يشتغل على كل الأجهزة")
                "mp3_128" -> "MP3 · 128 kbps" to L.t("Smaller file", "حجم أصغر")
                "m4a" -> "M4A · AAC" to L.t("Original quality, no re-encode", "الجودة الأصلية بدون تحويل")
                else -> "OPUS" to L.t("Best quality for its size", "أفضل جودة بالنسبة للحجم")
            }
            resultBox.addView(
                optionRow(title, sub2, fmtSize(o.sizeBytes, o.approx)) { startDownload(meta, o) },
                lp(8)
            )
        }
    }

    private fun optionRow(title: String, sub: String, size: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(cardC, 16, strokeC)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 16f, textC, true))
        texts.addView(tv(sub, 12f, mutedC))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tv(size, 14f, accentC, true))
        return row
    }

    private fun startDownload(meta: VideoMeta, opt: Option) {
        val url = currentUrl ?: return
        DownloadService.start(this, url, opt, meta.title)
        toast(L.t("Download started", "بدأ التحميل"))
    }

    // ---------- Downloaded tab ----------
    private fun exists(u: String): Boolean = try {
        contentResolver.openAssetFileDescriptor(Uri.parse(u), "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun renderDownloaded() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val all = History.all(applicationContext)
                val ok = all.filter { exists(it.uri) }
                if (ok.size != all.size) History.replaceQuietly(applicationContext, ok)
                ok
            }
            downloadedList.removeAllViews()
            if (i كل موقع وحقوق أصحاب المحتوى."
                ), 12f, mutedC
            ), lp(20)
        )

        // ---------- Layout + tabs ----------
        scrolls = listOf(page(home), page(dl), page(about))
        val frame = FrameLayout(this)
        scrolls.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        val labels = listOf(
            L.t("Download", "تحميل"),
            L.t("Downloaded", "التنزيلات"),
            L.t("About", "حول")
        )
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardC)
        }
        tabViews = labels.mapIndexed { i, label ->
            tv(label, 13f, mutedC, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener { selectTab(i) }
            }
        }
        tabViews.forEach { bottomBar.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgC)
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        outer.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        outer.addView(View(this).apply { setBackgroundColor(strokeC) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        outer.addView(bottomBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(outer)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val u = extractUrl(s?.toString())
                if (u != null && u != currentUrl) {
                    val r = Runnable { fetch(u) }
                    pending = r
                    handler.postDelayed(r, 600)
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Status.text.collect {
                        statusText.text = it.ifEmpty {
                            L.t(
                                "Paste a video link or share it from any app.",
                                "الصق رابط فيديو أو شاركه من أي تطبيق."
                            )
                        }
                    }
                }
                launch {
                    Status.progress.collect { p ->
                        when (p) {
                            -1 -> bar.visibility = View.GONE
                            -2 -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = true
                            }
                            else -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = false
                                bar.progress = p
                            }
                        }
                    }
                }
                launch {
                    History.version.collect { if (lastTab == 1) renderDownloaded() }
                }
            }
        }

        val meta = cachedMeta
        if (meta != null) {
            currentUrl = cachedUrl
            input.setText(cachedUrl ?: "")
            render(meta)
        }
        selectTab(lastTab)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun selectTab(i: Int) {
        lastTab = i
        scrolls.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { idx, t -> t.setTextColor(if (idx == i) accentC else mutedC) }
        if (i == 1) renderDownloaded()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val u = extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))
            i.action = null
            selectTab(0)
            if (u != null) {
                fetch(u)
                input.setText(u)
            } else {
                toast(L.t("No link found", "مفيش رابط"))
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/KeroEzzat01")))
        } catch (e: Exception) {
            toast(L.t("Couldn't open the link", "مقدرتش أفتح اللينك"))
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
        val u = extractUrl(t)
        if (u == null) {
            toast(L.t("No link in clipboard", "مفيش رابط في الحافظة"))
        } else {
            fetch(u)
            input.setText(u)
        }
    }

    private fun fetch(url: String) {
        pending?.let { handler.removeCallbacks(it) }
        fetchJob?.cancel()
        currentUrl = url
        resultBox.removeAllViews()
        Status.set(L.t("Analyzing link…", "جاري تحليل الرابط…"), -2)
        fetchJob = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { InfoFetcher.fetch(applicationContext, url) }
            }
            r.onSuccess { meta ->
                cachedUrl = url
                cachedMeta = meta
                render(meta)
                Status.set(L.t("Choose a quality to download", "اختر الجودة للتحميل"))
            }.onFailure { e ->
                Status.set(cleanError(e))
            }
        }
    }

    private fun render(meta: VideoMeta) {
        resultBox.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardC, 20, strokeC)
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        if (meta.thumb.isNotEmpty()) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(strokeC, 14)
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(190)))
            loadThumb(meta.thumb, img)
        }
        card.addView(tv(meta.title, 16f, textC, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(12))
        val sub = listOf(meta.uploader, fmtDuration(meta.duration))
            .filter { it.isNotEmpty() }.joinToString("  •  ")
        if (sub.isNotEmpty()) card.addView(tv(sub, 13f, mutedC), lp(4))
        resultBox.addView(card, lp(16))

        if (meta.video.isNotEmpty()) {
            resultBox.addView(tv(L.t("Video", "فيديو"), 13f, mutedC, true), lp(20))
            for (o in meta.video) {
                resultBox.addView(
                    optionRow("${o.height}p", "MP4", fmtSize(o.sizeBytes, o.approx)) {
                        startDownload(meta, o)
                    }, lp(8)
                )
            }
        }
        resultBox.addView(tv(L.t("Audio", "صوت"), 13f, mutedC, true), lp(20))
        for (o in meta.audio) {
            val (title, sub2) = when (o.type) {
                "mp3_320" -> "MP3 · 320 kbps" to L.t("Converted · plays everywhere", "محوّل · يشتغل على كل الأجهزة")
                "mp3_128" -> "MP3 · 128 kbps" to L.t("Smaller file", "حجم أصغر")
                "m4a" -> "M4A · AAC" to L.t("Original quality, no re-encode", "الجودة الأصلية بدون تحويل")
                else -> "OPUS" to L.t("Best quality for its size", "أفضل جودة بالنسبة للحجم")
            }
            resultBox.addView(
                optionRow(title, sub2, fmtSize(o.sizeBytes, o.approx)) { startDownload(meta, o) },
                lp(8)
            )
        }
    }

    private fun optionRow(title: String, sub: String, size: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(cardC, 16, strokeC)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 16f, textC, true))
        texts.addView(tv(sub, 12f, mutedC))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tv(size, 14f, accentC, true))
        return row
    }

    private fun startDownload(meta: VideoMeta, opt: Option) {
        val url = currentUrl ?: return
        DownloadService.start(this, url, opt, meta.title)
        toast(L.t("Download started", "بدأ التحميل"))
    }

    // ---------- Downloaded tab ----------
    private fun exists(u: String): Boolean = try {
        contentResolver.openAssetFileDescriptor(Uri.parse(u), "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun renderDownloaded() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val all = History.all(applicationContext)
                val ok = all.filter { exists(it.uri) }
                if (ok.size != all.size) History.replaceQuietly(applicationContext, ok)
                ok
            }
            downloadedList.removeAllViews()
            if (i
