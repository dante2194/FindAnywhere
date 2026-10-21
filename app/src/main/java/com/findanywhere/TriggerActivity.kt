package com.findanywhere

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Invisible activity other apps can launch.
 *   - Launch Activity "Find on screen"                       → toggles the find bar
 *   - Intent action com.findanywhere.action.SHOW/HIDE/TOGGLE → extras: query, fuzzy, ocr
 *   - URL  findanywhere://search?q=hello&mode=show&fuzzy=1&ocr=0
 */
class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = FindService.instance
        if (svc == null) {
            Toast.makeText(this, "Enable the Find Anywhere accessibility service first", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
            finish()
            return
        }

        val src = intent
        var action = src?.action ?: FindService.ACTION_TOGGLE
        var q: String? = src?.getStringExtra(FindService.EXTRA_QUERY)
        var fuzzy: Boolean? = if (src?.hasExtra(FindService.EXTRA_FUZZY) == true)
            src.getBooleanExtra(FindService.EXTRA_FUZZY, true) else null
        var ocr: Boolean = src?.getBooleanExtra(FindService.EXTRA_OCR, false) ?: false

        val data = src?.data
        if (data != null) {
            q = data.getQueryParameter("q") ?: q
            data.getQueryParameter("fuzzy")?.let { fuzzy = it == "1" || it.equals("true", true) }
            data.getQueryParameter("ocr")?.let { ocr = it == "1" || it.equals("true", true) }
            action = when (data.getQueryParameter("mode")?.lowercase()) {
                "hide" -> FindService.ACTION_HIDE
                "toggle" -> FindService.ACTION_TOGGLE
                "show" -> FindService.ACTION_SHOW
                else -> if (q.isNullOrEmpty()) FindService.ACTION_TOGGLE else FindService.ACTION_SHOW
            }
        }

        val out = Intent(action)
        if (q != null) out.putExtra(FindService.EXTRA_QUERY, q)
        if (fuzzy != null) out.putExtra(FindService.EXTRA_FUZZY, fuzzy!!)
        out.putExtra(FindService.EXTRA_OCR, ocr)
        svc.handleIntent(out)

        finish()
        overridePendingTransition(0, 0)
    }
}
