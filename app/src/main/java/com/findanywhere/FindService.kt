package com.findanywhere

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class FindService : AccessibilityService() {

    companion object {
        const val ACTION_TOGGLE = "com.findanywhere.action.TOGGLE"
        const val ACTION_SHOW = "com.findanywhere.action.SHOW"
        const val ACTION_HIDE = "com.findanywhere.action.HIDE"
        const val EXTRA_QUERY = "query"
        const val EXTRA_FUZZY = "fuzzy"
        const val EXTRA_OCR = "ocr"

        @Volatile
        var instance: FindService? = null
    }

    data class Entry(val text: String, val norm: String, val rect: Rect)

    // ---------- state ----------
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val prefs by lazy { getSharedPreferences("fa", MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var panel: PanelRoot? = null
    private var hl: HighlightView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bottom = false
    var showing = false
        private set

    private lateinit var etQuery: EditText
    private lateinit var tvCount: TextView
    private lateinit var btnFuzzy: TextView
    private lateinit var btnOcr: TextView
    private lateinit var histBox: LinearLayout

    private var entries: List<Entry> = emptyList()
    private var matches: List<Entry> = emptyList()
    private var cur = 0
    private var activeQuery = ""
    private var fuzzy = true
    private var forceOcr = false
    private var usedOcr = false
    private var deepTotal = -1
    private var busy = false
    private var job: Job? = null
    private var budget = 0
    private var receiverRegistered = false

    private val jumpRegex = Regex("^#\\d{1,4}$")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i != null) handleIntent(i)
        }
    }

    // ---------- service lifecycle ----------
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        fuzzy = prefs.getBoolean("fuzzy", true)
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(ACTION_TOGGLE)
                addAction(ACTION_SHOW)
                addAction(ACTION_HIDE)
            }
            ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        toggle()
                    }
                })
        } catch (_: Throwable) {
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hide()
        instance = null
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || panel == null || busy || forceOcr) return
        if (e.packageName?.toString() == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handler.removeCallbacks(rescanRunnable)
                handler.postDelayed(rescanRunnable, 450)
            }
        }
    }

    private val rescanRunnable = Runnable { if (panel != null) rescan(auto = false, quiet = true) }

    // ---------- external commands ----------
    fun handleIntent(i: Intent) {
        val q = i.getStringExtra(EXTRA_QUERY)
        if (i.hasExtra(EXTRA_FUZZY)) {
            fuzzy = i.getBooleanExtra(EXTRA_FUZZY, true)
            prefs.edit().putBoolean("fuzzy", fuzzy).apply()
        }
        val ocr = i.getBooleanExtra(EXTRA_OCR, false)
        when (i.action) {
            ACTION_HIDE -> hide()
            ACTION_SHOW -> show(q, ocr)
            else -> if (showing) hide() else show(q, ocr)
        }
    }

    fun toggle(collapseShade: Boolean = false) {
        if (showing) hide() else show(null, false, collapseShade)
    }

    fun show(q: String?, ocr: Boolean = false, collapse: Boolean = false) {
        if (panel != null) {
            if (q != null) etQuery.setText(q)
            if (ocr && !forceOcr) {
                forceOcr = true
                rescan(auto = false, quiet = false)
            }
            return
        }
        if (showing) return
        showing = true
        forceOcr = ocr
        if (collapse && Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        // small delay so a trigger activity / the shade can get out of the way
        handler.postDelayed({ build(q) }, if (collapse) 550L else 320L)
    }

    fun hide() {
        val typed = if (panel != null) etQuery.text.toString().trim() else ""
        handler.removeCallbacksAndMessages(null)
        job?.cancel()
        busy = false
        showing = false
        if (typed.isNotEmpty()) addHistory(typed)
        panel?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        hl?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        panel = null
        hl = null
        panelParams = null
        entries = emptyList()
        matches = emptyList()
        activeQuery = ""
        deepTotal = -1
        usedOcr = false
        forceOcr = false
    }

    // ---------- overlay construction ----------
    private fun rounded(color: Int, r: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

    private fun btn(label: String, click: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            minWidth = dp(38)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.argb(50, 255, 255, 255), 10)
            setOnClickListener { click() }
        }

    private fun gap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(6) }

    private fun build(q: String?) {
        if (panel != null) return
        val first = if (forceOcr) emptyList() else collectNodes()

        // highlight layer
        val h = HighlightView(this)
        val hp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            hp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(h, hp)
        } catch (t: Throwable) {
            toast("Overlay failed: ${t.message}")
            showing = false
            return
        }
        hl = h

        // find bar
        val p = buildPanel()
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(16),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(6)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        try {
            wm.addView(p, lp)
        } catch (t: Throwable) {
            toast("Overlay failed: ${t.message}")
            hide()
            return
        }
        panel = p
        panelParams = lp

        entries = first
        if (q != null) etQuery.setText(q)
        if (forceOcr || first.size < 4) {
            rescan(auto = true, quiet = true)   // tree (almost) empty → OCR fallback
        } else {
            refreshUi()
        }
        p.postDelayed({
            etQuery.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etQuery, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private val queryRunnable = Runnable {
        if (panel == null) return@Runnable
        val t = etQuery.text.toString().trim()
        if (!jumpRegex.matches(t)) {
            activeQuery = t
            deepTotal = -1
            cur = 0
            matches = filterMatches(entries)
            refreshUi()
        }
    }

    private fun buildPanel(): PanelRoot {
        val root = PanelRoot(this) { hide() }
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(8), dp(10), dp(8))
        root.background = rounded(Color.argb(246, 28, 30, 38), 18)

        // row 1 : query + prev/next/close
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etQuery = EditText(this).apply {
            hint = "Find on screen…  (#5 = jump to 5th)"
            setHintTextColor(Color.argb(140, 255, 255, 255))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(Color.argb(40, 255, 255, 255), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacks(queryRunnable)
                    handler.postDelayed(queryRunnable, 120)
                }
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                    commit()
                    true
                } else false
            }
        }
        row1.addView(etQuery, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(btn("▲") { prev() }, gap())
        row1.addView(btn("▼") { next() }, gap())
        row1.addView(btn("✕") { hide() }, gap())
        root.addView(row1)

        // row 2 : counter + tools
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row2.setPadding(0, dp(8), 0, 0)
        tvCount = TextView(this).apply {
            setTextColor(Color.argb(220, 255, 255, 255))
            textSize = 14f
            text = "type to find"
            setOnClickListener { rescan(auto = true, quiet = false) }   // tap = refresh
        }
        row2.addView(tvCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnFuzzy = btn("~") {
            fuzzy = !fuzzy
            prefs.edit().putBoolean("fuzzy", fuzzy).apply()
            matches = filterMatches(entries)
            cur = 0
            refreshUi()
            toast(if (fuzzy) "Fuzzy matching ON" else "Fuzzy matching OFF")
        }
        btnOcr = btn("OCR") {
            forceOcr = !forceOcr
            usedOcr = false
            toast(if (forceOcr) "OCR mode ON (reads pixels)" else "OCR mode OFF")
            rescan(auto = false, quiet = false)
        }
        val btnDeep = btn("⇣") { deepScan() }
        val btnCopy = btn("⧉") { copyVisible() }
        btnCopy.setOnLongClickListener { copyAll(); true }
        val btnHist = btn("🕘") { toggleHistory() }
        val btnFlip = btn("⇅") { flipPosition() }
        row2.addView(btnFuzzy, gap())
        row2.addView(btnOcr, gap())
        row2.addView(btnDeep, gap())
        row2.addView(btnCopy, gap())
        row2.addView(btnHist, gap())
        row2.addView(btnFlip, gap())
        root.addView(row2)

        histBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(histBox)
        return root
    }

    private fun flipPosition() {
        val p = panel ?: return
        val lp = panelParams ?: return
        bottom = !bottom
        lp.gravity = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        lp.y = dp(6)
        try {
            wm.updateViewLayout(p, lp)
        } catch (_: Throwable) {
        }
    }

    private fun setOverlayVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        panel?.visibility = vis
        hl?.visibility = vis
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun refreshUi() {
        val h = hl ?: return
        h.rects = matches.map { it.rect }
        h.current = if (matches.isEmpty()) -1 else cur
        h.invalidate()
        var t = when {
            busy -> "working…"
            activeQuery.isEmpty() -> "type to find"
            matches.isEmpty() -> "0 on screen"
            else -> "${cur + 1}/${matches.size}"
        }
        if (!busy && deepTotal >= 0 && activeQuery.isNotEmpty()) t += "  Σ$deepTotal"
        if (forceOcr || usedOcr) t += "  OCR"
        tvCount.text = t
        btnFuzzy.background = rounded(
            if (fuzzy) Color.argb(190, 0, 150, 170) else Color.argb(50, 255, 255, 255), 10)
        btnOcr.background = rounded(
            if (forceOcr) Color.argb(190, 255, 109, 0) else Color.argb(50, 255, 255, 255), 10)
    }

    // ---------- history ----------
    private fun history(): MutableList<String> =
        (prefs.getString("hist", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()

    private fun addHistory(q: String) {
        if (q.length < 2 || jumpRegex.matches(q)) return
        val h = history()
        h.remove(q)
        h.add(0, q)
        prefs.edit().putString("hist", h.take(30).joinToString("\n")).apply()
    }

    private fun toggleHistory() {
        if (histBox.visibility == View.VISIBLE) {
            histBox.visibility = View.GONE
            return
        }
        histBox.removeAllViews()
        val h = history().take(8)
        if (h.isEmpty()) {
            toast("No history yet")
            return
        }
        for (item in h) {
            val tv = TextView(this).apply {
                text = "🕘  $item"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    etQuery.setText(item)
                    etQuery.setSelection(item.length)
                    histBox.visibility = View.GONE
                }
            }
            histBox.addView(tv)
        }
        val clear = TextView(this).apply {
            text = "Clear history"
            setTextColor(Color.argb(200, 255, 138, 128))
            textSize = 13f
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener {
                prefs.edit().remove("hist").apply()
                histBox.visibility = View.GONE
            }
        }
        histBox.addView(clear)
        histBox.visibility = View.VISIBLE
    }

    // ---------- navigation ----------
    private fun key(e: Entry) = e.norm + "|" + e.rect.left

    private fun filterMatches(list: List<Entry>): List<Entry> {
        val q = Txt.norm(activeQuery)
        if (q.isEmpty()) return emptyList()
        return list.filter { Txt.matches(it.norm, q, fuzzy) }
    }

    private fun commit() {
        val t = etQuery.text.toString().trim()
        if (jumpRegex.matches(t)) {
            jumpTo(t.drop(1).toInt())
        } else {
            activeQuery = t
            addHistory(t)
            matches = filterMatches(entries)
            next()
        }
    }

    private fun next() {
        if (busy) return
        if (matches.isNotEmpty() && cur < matches.size - 1) {
            cur++
            refreshUi()
        } else if (activeQuery.isNotEmpty()) {
            if (activeQuery.length >= 2) addHistory(activeQuery)
            seek(true)
        }
    }

    private fun prev() {
        if (busy) return
        if (matches.isNotEmpty() && cur > 0) {
            cur--
            refreshUi()
        } else if (activeQuery.isNotEmpty()) {
            seek(false)
        }
    }

    // ---------- coroutine plumbing ----------
    private fun exclusive(quiet: Boolean = false, block: suspend () -> Unit) {
        if (busy) {
            if (!quiet) toast("Busy…")
            return
        }
        busy = true
        refreshUi()
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Error: ${e.message}")
            } finally {
                busy = false
                refreshUi()
            }
        }
    }

    private fun rescan(auto: Boolean, quiet: Boolean) = exclusive(quiet) {
        var e: List<Entry> = emptyList()
        usedOcr = false
        if (forceOcr) {
            e = ocr()
            usedOcr = true
        } else {
            e = collectNodes()
            if (auto && e.size < 4) {
                val o = ocr()
                if (o.isNotEmpty()) {
                    e = o
                    usedOcr = true
                }
            }
        }
        entries = e
        applyKeepingSelection()
    }

    private fun applyKeepingSelection() {
        val selKey = matches.getOrNull(cur)?.let { key(it) }
        matches = filterMatches(entries)
        val idx = if (selKey != null) matches.indexOfFirst { key(it) == selKey } else -1
        cur = if (idx >= 0) idx else cur.coerceIn(0, maxOf(0, matches.size - 1))
    }

    private suspend fun snapshot(): List<Entry> = if (forceOcr) ocr() else collectNodes()

    private fun sig(l: List<Entry>): Int = l.joinToString("\u0001") { it.text }.hashCode() * 31 + l.size

    /** Scroll the main scrollable one step. Returns true if the screen content changed. */
    private suspend fun scrollStep(forward: Boolean): Boolean {
        val before = sig(snapshot())
        val sc = findScrollable()
        if (sc != null) {
            sc.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        }
        delay(500)
        if (sig(snapshot()) != before) return true
        swipe(forward)
        delay(650)
        return sig(snapshot()) != before
    }

    private fun swipe(forward: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (forward) 0.72f else 0.30f)
        val y2 = dm.heightPixels * (if (forward) 0.30f else 0.72f)
        val path = Path().apply {
            moveTo(x, y1)
            lineTo(x, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        try {
            dispatchGesture(g, null, null)
        } catch (_: Throwable) {
        }
    }

    /** Next/previous match beyond the current screen: scroll + rescan until a new match shows up. */
    private fun seek(forward: Boolean) = exclusive {
        val seen = HashSet<String>()
        matches.forEach { seen.add(key(it)) }
        var found = false
        var steps = 0
        while (steps++ < 60) {
            val moved = scrollStep(forward)
            val e = snapshot()
            entries = e
            val m = filterMatches(e).filter { seen.add(key(it)) }
            if (m.isNotEmpty()) {
                matches = m
                cur = if (forward) 0 else m.size - 1
                found = true
                break
            }
            if (!moved) break
        }
        if (!found) toast(if (forward) "No more matches below" else "No more matches above")
    }

    /** "#N" — go to the Nth match counted from the top of the screen/list. */
    private fun jumpTo(n: Int) = exclusive {
        var g = 0
        while (g++ < 80 && scrollStep(false)) { /* back to top */ }
        var e = snapshot()
        entries = e
        var m = filterMatches(e)
        val seen = HashSet<String>()
        m.forEach { seen.add(key(it)) }
        var passed = 0
        var tries = 0
        while (passed + m.size < n && tries++ < 80) {
            passed += m.size
            val moved = scrollStep(true)
            e = snapshot()
            entries = e
            m = filterMatches(e).filter { seen.add(key(it)) }
            if (!moved && m.isEmpty()) break
        }
        matches = m
        if (passed + m.size >= n) {
            cur = n - passed - 1
        } else {
            cur = maxOf(0, m.size - 1)
            toast("Only ${passed + m.size} matches")
        }
    }

    private suspend fun crawl(onScreen: suspend (List<Entry>) -> Unit) {
        var g = 0
        while (g++ < 80 && scrollStep(false)) { /* to top */ }
        var steps = 0
        while (true) {
            onScreen(snapshot())
            if (steps++ >= 80) break
            if (!scrollStep(true)) break
        }
        g = 0
        while (g++ < 80 && scrollStep(false)) { /* back to top */ }
    }

    /** ⇣ : scroll through the whole list and count every match. */
    private fun deepScan() {
        if (activeQuery.isEmpty()) {
            toast("Type something first")
            return
        }
        exclusive {
            val keys = LinkedHashSet<String>()
            crawl { list -> for (m in filterMatches(list)) keys.add(key(m)) }
            deepTotal = keys.size
            entries = snapshot()
            matches = filterMatches(entries)
            cur = 0
        }
    }

    // ---------- copy ----------
    private fun clip(s: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("screen text", s))
    }

    private fun copyVisible() {
        val lines = entries.map { it.text }
        val out = ArrayList<String>()
        for (l in lines) if (out.isEmpty() || out.last() != l) out.add(l)
        clip(out.joinToString("\n"))
        toast("Copied ${out.size} lines (long-press = whole scrolling page)")
    }

    private fun copyAll() = exclusive {
        val seen = HashSet<String>()
        val sb = StringBuilder()
        crawl { list ->
            for (e in list) if (seen.add(key(e))) sb.append(e.text).append('\n')
        }
        clip(sb.toString().trim())
        toast("Copied ${seen.size} lines")
        entries = snapshot()
        matches = filterMatches(entries)
        cur = 0
    }

    // ---------- reading the screen ----------
    private fun nodeText(n: AccessibilityNodeInfo): String? {
        val a = n.text?.toString()
        if (!a.isNullOrBlank()) return a
        val b = n.contentDescription?.toString()
        if (!b.isNullOrBlank()) return b
        val c = n.hintText?.toString()
        if (!c.isNullOrBlank()) return c
        return null
    }

    private fun collectNodes(): List<Entry> {
        val out = ArrayList<Entry>()
        budget = 8000
        var any = false
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue
                any = true
                walk(r, out, 0)
            }
        } catch (_: Throwable) {
        }
        if (!any) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() != packageName) walk(r, out, 0)
        }
        out.sortWith(compareBy<Entry>({ it.rect.top }, { it.rect.left }))
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Entry>, depth: Int) {
        if (budget-- <= 0 || depth > 80) return
        if (!n.isVisibleToUser) return
        val t = nodeText(n)
        if (t != null) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val dm = resources.displayMetrics
            if (r.intersect(0, 0, dm.widthPixels, dm.heightPixels) && r.width() > 0 && r.height() > 0) {
                val s = if (t.length > 3000) t.substring(0, 3000) else t
                out.add(Entry(s, Txt.norm(s), r))
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, out, depth + 1)
        }
    }

    private fun findScrollable(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var left = 6000

        fun visit(n: AccessibilityNodeInfo, d: Int) {
            if (left-- <= 0 || d > 80 || !n.isVisibleToUser) return
            if (n.isScrollable) {
                val cn = n.className?.toString() ?: ""
                if (!cn.contains("ViewPager") && !cn.contains("HorizontalScrollView")) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    val a = r.width().toLong() * r.height().toLong()
                    if (a > bestArea) {
                        bestArea = a
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                visit(c, d + 1)
            }
        }

        try {
            var any = false
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue
                any = true
                visit(r, 0)
            }
            if (!any) {
                val r = rootInActiveWindow
                if (r != null && r.packageName?.toString() != packageName) visit(r, 0)
            }
        } catch (_: Throwable) {
        }
        return best
    }

    // ---------- OCR fallback (screenshot via the accessibility service, Android 11+) ----------
    private suspend fun ocr(): List<Entry> {
        if (Build.VERSION.SDK_INT < 30) {
            toast("OCR needs Android 11 or newer")
            return emptyList()
        }
        setOverlayVisible(false)
        val bmp: Bitmap?
        try {
            delay(220)
            bmp = screenshot()
        } finally {
            setOverlayVisible(true)
        }
        if (bmp == null) {
            toast("Screenshot failed or blocked by the app")
            return emptyList()
        }
        val res = recognize(bmp) ?: return emptyList()
        val out = ArrayList<Entry>()
        for (b in res.textBlocks) {
            for (l in b.lines) {
                val r = l.boundingBox ?: continue
                out.add(Entry(l.text, Txt.norm(l.text), Rect(r)))
            }
        }
        out.sortWith(compareBy<Entry>({ it.rect.top }, { it.rect.left }))
        return out
    }

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = result.hardwareBuffer
                            bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                        } catch (_: Throwable) {
                        }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private suspend fun recognize(bmp: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}
