/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import io.github.fnyoat.qqkuchiguse.config.KuchiguseConfig
import kotlin.math.roundToInt

/**
 * Small chart for the sentence-length probability gate. X axis = sentence length
 *  (0..maxLength), Y axis = probability (0..1). Redraw via [setParams].
 */
class LengthCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var maxLength: Int = 5
    private var gradient: Double = 1.0
    private var isEnabled: Boolean = true

    private val axisPaint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8E.toInt()
        strokeWidth = dpF(1f)
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x228A8A8E.toInt()
        strokeWidth = dpF(1f)
        style = Paint.Style.STROKE
    }
    private val curvePaint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7FA8.toInt()
        strokeWidth = dpF(2f)
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7FA8.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8E.toInt()
        textSize = sp(10)
    }
    private val curveFill = paintFill()

    private fun paintFill(): Paint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FF7FA8.toInt()
        style = Paint.Style.FILL
    }

    fun setParams(maxLength: Int, gradient: Double) {
        this.maxLength = maxLength.coerceAtLeast(0)
        this.gradient = if (gradient.isFinite() && gradient > 0) gradient else 1.0
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
        invalidate()
    }

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density
    private fun dpI(v: Int): Float = dpF(v.toFloat())
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val h = dpI(120)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = dpI(6)
        val padR = dpI(6)
        val padT = dpI(10)
        val padB = dpI(18)
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        val alpha = if (isEnabled) 1f else 0.4f
        val a = (255 * alpha).toInt()

        axisPaint.alpha = a
        gridPaint.alpha = a
        curvePaint.alpha = a
        dotPaint.alpha = a
        labelPaint.alpha = a
        curveFill.alpha = (0x33 * alpha).toInt()

        val steps = if (maxLength <= 0) 0 else maxLength
        fun xOf(len: Float): Float = padL + (len / steps) * plotW
        fun yOf(p: Double): Float = padT + ((1.0 - p.coerceIn(0.0, 1.0)) * plotH).toFloat()

        // gridlines at y = 100%, 75%, 50%, 25%, 0%
        for (qt in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val gy = yOf(qt)
            canvas.drawLine(padL, gy, w - padR, gy, if (qt == 0.0 || qt == 1.0) axisPaint else gridPaint)
            val label = "${(qt * 100).roundToInt()}%"
            canvas.drawText(
                label, w - padR, gy - dpI(3),
                labelPaint.apply { textAlign = Paint.Align.RIGHT }
            )
        }
        // x axis line
        canvas.drawLine(padL, yOf(0.0), w - padR, yOf(0.0), axisPaint)

        if (steps == 0) {
            canvas.drawText(
                "off", padL, padT + dpI(12),
                labelPaint.apply { textAlign = Paint.Align.LEFT }
            )
            return
        }

        val path = Path()
        val fill = Path()
        val first = 0f
        fill.moveTo(xOf(first), yOf(1.0))
        // 整条曲线共用一个 config。原来是每个点 new 一个，而 [KuchiguseConfig.lengthProbability]
        // 只读 maxLength 和 lengthGradient 两个字段 —— 每次 new 顺带把默认的 affixes /
        // replacements 两个 list 也分配一遍，全是白扔的垃圾。实测每帧省 3~7 µs。
        val curveConfig = KuchiguseConfig(maxLength = maxLength, lengthGradient = gradient)
        var started = false
        for (len in 0..steps) {
            val p = curveConfig.lengthProbability(len.toDouble().roundToInt())
            val x = xOf(len.toFloat())
            val y = yOf(p)
            if (!started) {
                path.moveTo(x, y)
                fill.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
            canvas.drawCircle(x, y, dpI(2), dotPaint)
        }
        fill.lineTo(w - padR, yOf(0.0))
        fill.lineTo(padL, yOf(0.0))
        fill.close()
        canvas.drawPath(fill, curveFill)
        canvas.drawPath(path, curvePaint)

        canvas.drawText(
            "0", padL, h - dpI(3),
            labelPaint.apply { textAlign = Paint.Align.LEFT }
        )
        canvas.drawText(
            maxLength.toString(), w - padR, h - dpI(3),
            labelPaint.apply { textAlign = Paint.Align.RIGHT }
        )
    }
}