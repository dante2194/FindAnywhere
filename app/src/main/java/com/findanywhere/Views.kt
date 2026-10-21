package com.findanywhere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout

/** Full-screen, non-touchable layer that draws match boxes in screen coordinates. */
class HighlightView(ctx: Context) : View(ctx) {
    var rects: List<Rect> = emptyList()
    var current: Int = -1

    private val loc = IntArray(2)
    private val rf = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 214, 0)
        style = Paint.Style.FILL
    }
    private val curFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 109, 0)
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 109, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 179, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        for ((i, r) in rects.withIndex()) {
            rf.set(
                (r.left - loc[0]).toFloat() - 3f,
                (r.top - loc[1]).toFloat() - 3f,
                (r.right - loc[0]).toFloat() + 3f,
                (r.bottom - loc[1]).toFloat() + 3f
            )
            if (i == current) {
                canvas.drawRoundRect(rf, 10f, 10f, curFill)
                canvas.drawRoundRect(rf, 10f, 10f, stroke)
            } else {
                canvas.drawRoundRect(rf, 8f, 8f, fill)
                canvas.drawRoundRect(rf, 8f, 8f, thin)
            }
        }
    }
}

/** Root of the find bar; closes it on Back. */
class PanelRoot(ctx: Context, private val onBack: () -> Unit) : LinearLayout(ctx) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
