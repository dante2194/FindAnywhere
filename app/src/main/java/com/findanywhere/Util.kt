package com.findanywhere

import android.content.Context
import java.text.Normalizer

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Txt {

    /** Lower-case, strip diacritics, unify Arabic/Persian letter variants & digits. */
    fun norm(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        for (c in d) {
            val type = Character.getType(c)
            when {
                type == Character.NON_SPACING_MARK.toInt() -> {}
                c == '\u200c' || c == '\u0640' -> {}
                c == '\u064a' || c == '\u0649' -> sb.append('\u06cc')
                c == '\u0643' -> sb.append('\u06a9')
                c in '\u06f0'..'\u06f9' -> sb.append('0' + (c - '\u06f0'))
                c in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                c == '\n' || c == '\t' || c == '\r' -> sb.append(' ')
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /** Approximate substring search (Sellers): does [pat] occur in [text] with <= k edits? */
    fun approx(text: String, pat: String, k: Int): Boolean {
        val m = pat.length
        if (m == 0) return true
        if (k <= 0) return text.contains(pat)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (c in text) {
            cur[0] = 0
            for (i in 1..m) {
                val cost = if (pat[i - 1] == c) 0 else 1
                cur[i] = minOf(prev[i - 1] + cost, prev[i] + 1, cur[i - 1] + 1)
            }
            if (cur[m] <= k) return true
            val t = prev
            prev = cur
            cur = t
        }
        return false
    }

    private fun kFor(len: Int): Int = if (len <= 2) 0 else maxOf(1, len / 4)

    fun matches(textNorm: String, queryNorm: String, fuzzy: Boolean): Boolean {
        if (queryNorm.isEmpty()) return false
        if (textNorm.contains(queryNorm)) return true
        if (!fuzzy) return false
        val toks = queryNorm.split(' ').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return false
        return toks.all { approx(textNorm, it, kFor(it.length)) }
    }
}
