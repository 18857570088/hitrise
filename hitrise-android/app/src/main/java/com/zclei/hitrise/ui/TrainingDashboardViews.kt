package com.zclei.hitrise.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

class CircularTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#173247")
        }
    private val progressPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#2E75B6")
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    private val captionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C8FFE0")
            textAlign = Paint.Align.CENTER
        }
    private val bounds = RectF()
    private var progress = 1f
    private var centerText = "00:00"
    private var captionText = ""
    private var textColor = Color.WHITE

    fun setTimerState(
        progressFraction: Float,
        center: String,
        caption: String,
        color: Int,
    ) {
        progress = progressFraction.coerceIn(0f, 1f)
        centerText = center
        captionText = caption
        progressPaint.color = color
        textPaint.color = color
        invalidate()
    }

    fun setPalette(
        trackColor: Int,
        captionColor: Int,
        centerColor: Int,
    ) {
        trackPaint.color = trackColor
        captionPaint.color = captionColor
        textColor = centerColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val stroke = size * 0.075f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        val pad = stroke / 2f + 4f
        bounds.set((width - size) / 2f + pad, (height - size) / 2f + pad, (width + size) / 2f - pad, (height + size) / 2f - pad)
        canvas.drawArc(bounds, -90f, 360f, false, trackPaint)
        canvas.drawArc(bounds, -90f, 360f * progress, false, progressPaint)
        if (progressPaint.color == Color.TRANSPARENT) {
            textPaint.color = textColor
        }
        textPaint.textSize = size * 0.2f
        captionPaint.textSize = size * 0.085f
        canvas.drawText(centerText, width / 2f, height / 2f - size * 0.01f, textPaint)
        canvas.drawText(captionText, width / 2f, height / 2f + size * 0.15f, captionPaint)
    }
}

class PunchWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guidePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#35536A")
            strokeWidth = 2f
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DFFFF0")
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
        }
    private val values = ArrayDeque<Float>()
    private var latestForce = 0f
    private var peakForce = 0f
    private var emptyLabel = "Waiting for punch force"
    private var latestLabel = "Latest"
    private var peakLabel = "Peak"
    private var lowForceColor = LOW_FORCE_COLOR
    private var midForceColor = MID_FORCE_COLOR
    private var highForceColor = HIGH_FORCE_COLOR

    fun setLabelText(
        empty: String,
        latest: String,
        peak: String,
    ) {
        emptyLabel = empty
        latestLabel = latest
        peakLabel = peak
        invalidate()
    }

    fun setPalette(
        guideColor: Int,
        labelColor: Int,
        lowColor: Int,
        midColor: Int,
        highColor: Int,
    ) {
        guidePaint.color = guideColor
        labelPaint.color = labelColor
        lowForceColor = lowColor
        midForceColor = midColor
        highForceColor = highColor
        invalidate()
    }

    fun reset() {
        values.clear()
        latestForce = 0f
        peakForce = 0f
        invalidate()
    }

    fun addForce(forceN: Float) {
        val force = forceN.coerceAtLeast(0f)
        latestForce = force
        peakForce = maxOf(peakForce, force)
        values.addLast(force)
        while (values.size > MAX_BARS) {
            values.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(0f, h * 0.72f, w, h * 0.72f, guidePaint)
        if (values.isEmpty()) {
            labelPaint.textSize = min(w, h) * 0.16f
            canvas.drawText(emptyLabel, 10f, h * 0.55f, labelPaint)
            return
        }
        val gap = 3f
        val barWidth = ((w - gap * (MAX_BARS - 1)) / MAX_BARS).coerceAtLeast(3f)
        val start = w - values.size * (barWidth + gap)
        val minForce = values.minOrNull() ?: 0f
        val maxForce = values.maxOrNull() ?: 0f
        val scale = maxOf(120f, maxForce * 1.15f)
        values.forEachIndexed { index, force ->
            val normalized = (force / scale).coerceIn(0.08f, 1f)
            val barHeight = normalized * (h - 8f)
            barPaint.color = forceColor(force, minForce, maxForce)
            val left = start + index * (barWidth + gap)
            canvas.drawRoundRect(left, h - barHeight, left + barWidth, h, barWidth / 2f, barWidth / 2f, barPaint)
        }
        labelPaint.textSize = min(w, h) * 0.15f
        canvas.drawText("$latestLabel ${latestForce.roundToInt()} N   $peakLabel ${peakForce.roundToInt()} N", 10f, labelPaint.textSize + 6f, labelPaint)
    }

    private companion object {
        const val MAX_BARS = 42
        val LOW_FORCE_COLOR: Int = Color.parseColor("#A7F3D0")
        val MID_FORCE_COLOR: Int = Color.parseColor("#FFD060")
        val HIGH_FORCE_COLOR: Int = Color.parseColor("#8B0000")
    }

    private fun forceColor(force: Float, minForce: Float, maxForce: Float): Int {
        val normalized =
            if (maxForce > minForce) {
                (force - minForce) / (maxForce - minForce)
            } else {
                (force / 120f).coerceIn(0f, 1f)
            }
        return if (normalized <= 0.5f) {
            blendColor(lowForceColor, midForceColor, normalized / 0.5f)
        } else {
            blendColor(midForceColor, highForceColor, (normalized - 0.5f) / 0.5f)
        }
    }

    private fun blendColor(start: Int, end: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val r = Color.red(start) + ((Color.red(end) - Color.red(start)) * t).roundToInt()
        val g = Color.green(start) + ((Color.green(end) - Color.green(start)) * t).roundToInt()
        val b = Color.blue(start) + ((Color.blue(end) - Color.blue(start)) * t).roundToInt()
        return Color.rgb(r, g, b)
    }
}
