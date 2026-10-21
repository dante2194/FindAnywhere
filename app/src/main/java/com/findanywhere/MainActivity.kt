package com.findanywhere

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#12141A"))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }
        sv.addView(col)

        col.addView(text("Find Anywhere", 30f, Color.WHITE, true))
        col.addView(text("Ctrl+F for every screen — Settings, system apps, anything.", 15f, Color.parseColor("#9AA3B2"), false).apply {
            setPadding(0, dp(4), 0, dp(20))
        })

        status = text("", 16f, Color.WHITE, true).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(status, lpFull(0, 0, 0, 16))

        col.addView(heading("Setup"))
        col.addView(button("1 · Allow restricted settings (App info → ⋮ menu)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, lpFull(0, 6, 0, 0))
        col.addView(button("2 · Turn on the accessibility service") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lpFull(0, 8, 0, 0))
        col.addView(button("3 · Xiaomi: Autostart / pop-up permissions") {
            try {
                val i = Intent("miui.intent.action.APP_PERM_EDITOR")
                i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                i.putExtra("extra_pkgname", packageName)
                startActivity(i)
            } catch (_: Throwable) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }, lpFull(0, 8, 0, 0))
        col.addView(button("4 · Battery: set to No restrictions") {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, lpFull(0, 8, 0, 0))
        col.addView(button("▶ Test: show find bar over the next app (5 s)") {
            if (FindService.instance == null) {
                Toast.makeText(this, "Enable the service first (step 2)", Toast.LENGTH_LONG).show()
            } else {
                moveTaskToBack(true)
                Handler(Looper.getMainLooper()).postDelayed({ FindService.instance?.show(null) }, 5000)
            }
        }, lpFull(0, 14, 0, 0))

        col.addView(heading("Ways to trigger it"))
        col.addView(text(
            "• Quick Settings tile “Find on screen” (edit tiles → add it)\n" +
            "• Accessibility button / shortcut (assign this service)\n" +
            "• MacroDroid ▸ Launch Activity ▸ “Find on screen”\n" +
            "• MacroDroid ▸ Send Intent (target: Broadcast)\n" +
            "    Action: com.findanywhere.action.SHOW\n" +
            "    (or .TOGGLE / .HIDE)\n" +
            "    Extras: query=hello  fuzzy=true  ocr=false\n" +
            "• MacroDroid ▸ Open URL\n" +
            "    findanywhere://search?q=hello&mode=show\n",
            14f, Color.parseColor("#C9D1E0"), false).apply {
            typeface = Typeface.MONOSPACE
        })

        col.addView(heading("In the find bar"))
        col.addView(text(
            "▲ ▼  previous / next (auto-scrolls to off-screen matches)\n" +
            "~    fuzzy matching on/off (typo tolerant)\n" +
            "OCR  read pixels instead of the UI tree\n" +
            "     (auto-used when a screen exposes no text)\n" +
            "⇣    scroll the whole list and count all matches\n" +
            "⧉    copy screen text · long-press = whole page\n" +
            "🕘   search history\n" +
            "⇅    move the bar to top / bottom\n" +
            "#5 + Enter  jump to the 5th match\n" +
            "Tap the counter to rescan.",
            14f, Color.parseColor("#C9D1E0"), false).apply {
            typeface = Typeface.MONOSPACE
        })

        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        val on = serviceEnabled()
        status.text = if (on) "●  Service is running" else "●  Service is OFF — follow the steps below"
        status.setTextColor(if (on) Color.parseColor("#69F0AE") else Color.parseColor("#FF8A80"))
        status.background = GradientDrawable().apply {
            setColor(Color.parseColor("#1C202B"))
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains("$packageName/") && s.contains("FindService")
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (_: Throwable) {
            Toast.makeText(this, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun lpFull(l: Int, t: Int, r: Int, b: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun heading(s: String) = text(s.uppercase(), 12f, Color.parseColor("#7C8AFF"), true).apply {
        setPadding(0, dp(24), 0, dp(4))
        letterSpacing = 0.12f
    }

    private fun button(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A3050"))
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { click() }
    }
}
