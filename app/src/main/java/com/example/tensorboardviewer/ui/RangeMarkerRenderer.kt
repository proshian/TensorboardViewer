package com.example.tensorboardviewer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RangeMarkerRenderer(
    chart: LineDataProvider,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : LineChartRenderer(chart, animator, viewPortHandler) {

    var rangeStart: Float? = null
    var rangeEnd: Float? = null
    var isRangeSelectionEnabled = false

    private val linePaint = Paint().apply {
        color = Color.rgb(255, 0, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val handlePaint = Paint().apply {
        color = Color.rgb(255, 100, 100)
        style = Paint.Style.FILL
    }

    private val outsideShadePaint = Paint().apply {
        color = Color.argb(220, 255, 255, 255) // Very pale white overlay for outside
        style = Paint.Style.FILL
    }
    
    private val insideShadePaint = Paint().apply {
        color = Color.argb(15, 255, 100, 100) // Very light red tint matching handle color
        style = Paint.Style.FILL
    }

    private val handleRadius = 30f
    private var draggingLine: Int? = null // 1 for start, 2 for end

    fun updateRange(start: Float?, end: Float?) {
        rangeStart = start
        rangeEnd = end
    }

    override fun drawExtras(c: Canvas) {
        super.drawExtras(c)

        if (!isRangeSelectionEnabled || rangeStart == null || rangeEnd == null) return
        if (mChart.data == null || mChart.data.dataSets.isEmpty()) return

        val transformer = mChart.getTransformer(mChart.data.dataSets[0].axisDependency)
        val viewPortHandler = mViewPortHandler

        // Convert X values to pixels
        val startPx = transformer.getPixelForValues(rangeStart!!, 0f).x.toFloat()
        val endPx = transformer.getPixelForValues(rangeEnd!!, 0f).x.toFloat()

        val contentTop = viewPortHandler.contentTop().toFloat()
        val contentBottom = viewPortHandler.contentBottom().toFloat()
        val contentLeft = viewPortHandler.contentLeft()
        val contentRight = viewPortHandler.contentRight()

        // Draw light colored tint inside the selected range
        c.drawRect(startPx, contentTop, endPx, contentBottom, insideShadePaint)

        // Draw pale white overlay on left side (before range start)
        c.drawRect(contentLeft, contentTop, startPx, contentBottom, outsideShadePaint)
        
        // Draw pale white overlay on right side (after range end)
        c.drawRect(endPx, contentTop, contentRight, contentBottom, outsideShadePaint)

        // Draw vertical lines
        c.drawLine(startPx, contentTop, startPx, contentBottom, linePaint)
        c.drawLine(endPx, contentTop, endPx, contentBottom, linePaint)

        // Draw draggable handles at the bottom
        val handleY = contentBottom - handleRadius * 2

        // Start handle
        c.drawCircle(startPx, handleY, handleRadius, handlePaint)

        // End handle
        c.drawCircle(endPx, handleY, handleRadius, handlePaint)
    }

    fun handleTouch(event: MotionEvent): Boolean {
        if (!isRangeSelectionEnabled || rangeStart == null || rangeEnd == null) return false
        if (mChart.data == null || mChart.data.dataSets.isEmpty()) return false

        val transformer = mChart.getTransformer(mChart.data.dataSets[0].axisDependency)
        val viewPortHandler = mViewPortHandler

        val startPx = transformer.getPixelForValues(rangeStart!!, 0f).x.toFloat()
        val endPx = transformer.getPixelForValues(rangeEnd!!, 0f).x.toFloat()

        val contentBottom = viewPortHandler.contentBottom().toFloat()
        val handleY = contentBottom - handleRadius * 2

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if touching start handle
                if (abs(event.x - startPx) < handleRadius * 2 && abs(event.y - handleY) < handleRadius * 2) {
                    draggingLine = 1
                    return true
                }
                // Check if touching end handle
                if (abs(event.x - endPx) < handleRadius * 2 && abs(event.y - handleY) < handleRadius * 2) {
                    draggingLine = 2
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingLine != null) {
                    // Convert pixel to value
                    val newX = transformer.getValuesByTouchPoint(event.x, 0f).x.toFloat()
                    
                    if (draggingLine == 1) {
                        rangeStart = max(0f, min(newX, rangeEnd!! - 1f)) // Ensure start < end and >= 0
                    } else {
                        rangeEnd = max(1f, max(newX, rangeStart!! + 1f)) // Ensure end > start and >= 1
                    }
                    
                    (mChart as? com.github.mikephil.charting.charts.LineChart)?.invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingLine = null
            }
        }
        return false
    }
}
