package com.shadowlook.app.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.shadowlook.app.ml.FaceAnalyzer

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<FaceAnalyzer.FaceRecognitionResult> = emptyList()
    private var scaleFactor: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // ألوان الثيم السيبراني
    private val neonCyan = Color.parseColor("#00F0FF")
    private val neonRed = Color.parseColor("#FF0055")
    private val neonGreen = Color.parseColor("#00FF66")
    private val darkBg = Color.parseColor("#0B0E14")

    private val knownBoxPaint = Paint().apply {
        color = neonCyan
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = null
    }

    private val unknownBoxPaint = Paint().apply {
        color = neonRed
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val textBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val smallTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    fun setResults(results: List<FaceAnalyzer.FaceRecognitionResult>) {
        this.results = results
        invalidate()
    }

    fun setImageSize(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0) return

        // حساب scaleFactor للحفاظ على نسبة العرض
        val viewAspectRatio = width.toFloat() / height.toFloat()
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()

        if (viewAspectRatio > imageAspectRatio) {
            scaleFactor = height.toFloat() / imageHeight.toFloat()
            offsetX = (width - imageWidth * scaleFactor) / 2f
            offsetY = 0f
        } else {
            scaleFactor = width.toFloat() / imageWidth.toFloat()
            offsetX = 0f
            offsetY = (height - imageHeight * scaleFactor) / 2f
        }

        for (result in results) {
            val boundingBox = result.boundingBox
            val isKnown = result.isKnown

            // تحويل الإحداثيات
            val left = boundingBox.left * scaleFactor + offsetX
            val top = boundingBox.top * scaleFactor + offsetY
            val right = boundingBox.right * scaleFactor + offsetX
            val bottom = boundingBox.bottom * scaleFactor + offsetY

            val rect = RectF(left, top, right, bottom)

            if (isKnown) {
                drawKnownFace(canvas, rect, result)
            } else {
                drawUnknownFace(canvas, rect, result)
            }
        }
    }

    private fun drawKnownFace(canvas: Canvas, rect: RectF, result: FaceAnalyzer.FaceRecognitionResult) {
        // مربع نيون سماوي للمعروفين
        knownBoxPaint.color = neonCyan
        canvas.drawRoundRect(rect, 16f, 16f, knownBoxPaint)

        // زوايا HUD
        cornerPaint.color = neonCyan
        drawCorners(canvas, rect, cornerPaint)

        // خلفية النص
        val label = result.userName ?: "معروف"
        val confidence = ((1 - result.distance) * 100).toInt().coerceIn(0, 100)
        val text = "$label • $confidence%"

        val textWidth = textPaint.measureText(text)
        val textHeight = 50f
        val textBgRect = RectF(
            rect.left,
            rect.top - textHeight - 10,
            rect.left + textWidth + 32,
            rect.top - 10
        )

        textBackgroundPaint.color = neonCyan
        textBackgroundPaint.alpha = 230
        canvas.drawRoundRect(textBgRect, 8f, 8f, textBackgroundPaint)

        // نص الاسم والثقة
        textPaint.color = darkBg
        canvas.drawText(text, textBgRect.left + 16, textBgRect.bottom - 12, textPaint)

        // مؤشر حالة أخضر صغير
        val statusPaint = Paint().apply {
            color = neonGreen
            style = Paint.Style.FILL
        }
        canvas.drawCircle(rect.right - 20, rect.top + 20, 10f, statusPaint)

        // خط توهج خارجي
        val glowPaint = Paint().apply {
            color = neonCyan
            style = Paint.Style.STROKE
            strokeWidth = 12f
            alpha = 30
            isAntiAlias = true
        }
        canvas.drawRoundRect(rect, 16f, 16f, glowPaint)
    }

    private fun drawUnknownFace(canvas: Canvas, rect: RectF, result: FaceAnalyzer.FaceRecognitionResult) {
        // مربع أحمر للمجهولين مع تأثير وميض
        unknownBoxPaint.color = neonRed
        canvas.drawRoundRect(rect, 16f, 16f, unknownBoxPaint)

        // زوايا حمراء
        cornerPaint.color = neonRed
        drawCorners(canvas, rect, cornerPaint)

        // خلفية تحذير حمراء
        val warningText = "⚠️ مجهول!"
        val textWidth = textPaint.measureText(warningText)
        val textHeight = 50f
        val textBgRect = RectF(
            rect.left,
            rect.top - textHeight - 10,
            rect.left + textWidth + 32,
            rect.top - 10
        )

        textBackgroundPaint.color = neonRed
        textBackgroundPaint.alpha = 230
        canvas.drawRoundRect(textBgRect, 8f, 8f, textBackgroundPaint)

        textPaint.color = Color.WHITE
        canvas.drawText(warningText, textBgRect.left + 16, textBgRect.bottom - 12, textPaint)

        // خطوط تحذير قطرية
        val warningLinePaint = Paint().apply {
            color = neonRed
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = 100
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, warningLinePaint)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, warningLinePaint)

        // توهج أحمر
        val glowPaint = Paint().apply {
            color = neonRed
            style = Paint.Style.STROKE
            strokeWidth = 14f
            alpha = 40
            isAntiAlias = true
        }
        canvas.drawRoundRect(rect, 16f, 16f, glowPaint)
    }

    private fun drawCorners(canvas: Canvas, rect: RectF, paint: Paint) {
        val cornerLength = 30f

        // Top-left
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLength, rect.top, paint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, paint)

        // Top-right
        canvas.drawLine(rect.right - cornerLength, rect.top, rect.right, rect.top, paint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, paint)

        // Bottom-left
        canvas.drawLine(rect.left, rect.bottom - cornerLength, rect.left, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLength, rect.bottom, paint)

        // Bottom-right
        canvas.drawLine(rect.right - cornerLength, rect.bottom, rect.right, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.bottom - cornerLength, rect.right, rect.bottom, paint)
    }
}
