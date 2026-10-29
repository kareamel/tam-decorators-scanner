package com.tamdecorators.scanner.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withSave

class DocumentOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0xFF2D6BFF.toInt() // blue border
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66000000
    }

    private val path = Path()
    private var quad: FloatArray? = null // x0,y0,x1,y1,x2,y2,x3,y3 in view coords

    fun updateQuad(viewQuad: FloatArray?) {
        quad = viewQuad
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        if (q.size < 8) return

        path.reset()
        path.moveTo(q[0], q[1])
        path.lineTo(q[2], q[3])
        path.lineTo(q[4], q[5])
        path.lineTo(q[6], q[7])
        path.close()

        canvas.withSave {
            // Subtle dim outside (simple: draw full dim, then clear not supported; keep minimal)
            // We keep it tiny-scanner-like with just outline for now.
            drawPath(path, paint)
        }
    }
}

