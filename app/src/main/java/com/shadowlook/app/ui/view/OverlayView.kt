package com.shadowlook.app.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.shadowlook.app.ml.FaceAnalyzer

/**
 * ShadowLook v2.0 - Improved Box Design
 * - Known: Green #00FF66 with glow + beautiful popup
 * - Unknown: Red #FF0055 with warning + auto capture
 */
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

    // ألوان جديدة حسب الطلب
    private val neonGreen = Color.parseColor("#00FF66") // للمعروفين - أخضر
    private val neonRed = Color.parseColor("#FF0055")   // للمجهولين - أحمر
    private val neonCyan = Color.parseColor("#00F0FF")
    private val darkBg = Color.parseColor("#0B0E14")

    private val knownBoxPaint = Paint().apply {
        color = neonGreen
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val unknownBoxPaint = Paint().apply {
        color = neonRed
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val textBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val smallTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    fun setResults(results: List<FaceAnalyzer.FaceRecognitionResult>) {
        try {
            this.results = results
            invalidate()
        } catch (e: Throwable) {
            Log.e("OverlayView", "خطأ في setResults: ${e.message}", e)
        }
    }

    fun setImageSize(width: Int, height: Int) {
        try {
            imageWidth = width
            imageHeight = height
        } catch (e: Throwable) {
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            super.onDraw(canvas)

            if (imageWidth == 0 || imageHeight == 0) return
            if (results.isEmpty()) return

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
                try {
                    val boundingBox = result.boundingBox
                    if (boundingBox.isEmpty) continue

                    val left = boundingBox.left * scaleFactor + offsetX
                    val top = boundingBox.top * scaleFactor + offsetY
                    val right = boundingBox.right * scaleFactor + offsetX
                    val bottom = boundingBox.bottom * scaleFactor + offsetY

                    if (left >= right || top >= bottom) continue

                    val rect = RectF(left, top, right, bottom)

                    if (result.isKnown) {
                        drawKnownFaceGreen(canvas, rect, result)
                    } else {
                        drawUnknownFaceRed(canvas, rect, result)
                    }
                } catch (e: Throwable) {
                    Log.e("OverlayView", "خطأ في رسم وجه: ${e.message}", e)
                }
            }
        } catch (e: Throwable) {
            Log.e("OverlayView", "خطأ في onDraw: ${e.message}", e)
        }
    }

    /**
     * معروف: مربع أخضر #00FF66 مع توهج أخضر + بيانات جميلة
     */
    private fun drawKnownFaceGreen(canvas: Canvas, rect: RectF, result: FaceAnalyzer.FaceRecognitionResult) {
        try {
            // مربع أخضر للمعروفين
            knownBoxPaint.color = neonGreen
            canvas.drawRoundRect(rect, 18f, 18f, knownBoxPaint)

            // زوايا خضراء سميكة
            cornerPaint.color = neonGreen
            drawCornersEnhanced(canvas, rect, cornerPaint)

            // خلفية النص - أخضر
            val label = result.userName ?: "معروف"
            val confidence = ((1 - result.distance) * 100).toInt().coerceIn(0, 100)
            val qualityText = "جودة: ${(result.quality * 100).toInt()}%"
            val text = "$label • $confidence%"

            val textWidth = textPaint.measureText(text)
            val textHeight = 52f
            val textBgRect = RectF(
                rect.left,
                (rect.top - textHeight - 12).coerceAtLeast(0f),
                rect.left + textWidth + 36,
                rect.top - 12
            )

            if (textBgRect.top >= 0) {
                textBackgroundPaint.color = neonGreen
                textBackgroundPaint.alpha = 240
                canvas.drawRoundRect(textBgRect, 10f, 10f, textBackgroundPaint)

                textPaint.color = darkBg
                canvas.drawText(text, textBgRect.left + 18, textBgRect.bottom - 14, textPaint)

                // جودة صغيرة تحت
                if (result.quality < 1f) {
                    smallTextPaint.color = darkBg
                    smallTextPaint.alpha = 180
                    canvas.drawText(qualityText, textBgRect.left + 18, textBgRect.top - 6, smallTextPaint)
                }
            }

            // مؤشر حالة أخضر نابض
            val statusPaint = Paint().apply {
                color = neonGreen
                style = Paint.Style.FILL
            }
            canvas.drawCircle(rect.right - 22, rect.top + 22, 12f, statusPaint)
            
            // حلقة خارجية نابضة
            val pulsePaint = Paint().apply {
                color = neonGreen
                style = Paint.Style.STROKE
                strokeWidth = 3f
                alpha = 100
            }
            canvas.drawCircle(rect.right - 22, rect.top + 22, 18f, pulsePaint)

            // توهج أخضر خارجي جميل
            val glowPaint = Paint().apply {
                color = neonGreen
                style = Paint.Style.STROKE
                strokeWidth = 16f
                alpha = 35
                isAntiAlias = true
            }
            canvas.drawRoundRect(rect, 18f, 18f, glowPaint)

            // خط إضافي داخلي
            val innerGlowPaint = Paint().apply {
                color = neonGreen
                style = Paint.Style.STROKE
                strokeWidth = 2f
                alpha = 80
            }
            val innerRect = RectF(rect.left + 4, rect.top + 4, rect.right - 4, rect.bottom - 4)
            canvas.drawRoundRect(innerRect, 14f, 14f, innerGlowPaint)

        } catch (e: Throwable) {
            Log.e("OverlayView", "خطأ في drawKnownFaceGreen: ${e.message}", e)
        }
    }

    /**
     * مجهول: مربع أحمر #FF0055 مع تحذير وخطوط قطرية
     */
    private fun drawUnknownFaceRed(canvas: Canvas, rect: RectF, result: FaceAnalyzer.FaceRecognitionResult) {
        try {
            unknownBoxPaint.color = neonRed
            canvas.drawRoundRect(rect, 18f, 18f, unknownBoxPaint)

            cornerPaint.color = neonRed
            drawCornersEnhanced(canvas, rect, cornerPaint)

            val warningText = "⚠️ مجهول! ${((1 - result.distance) * 100).toInt()}%"
            val textWidth = textPaint.measureText(warningText)
            val textHeight = 52f
            val textBgRect = RectF(
                rect.left,
                (rect.top - textHeight - 12).coerceAtLeast(0f),
                rect.left + textWidth + 36,
                rect.top - 12
            )

            if (textBgRect.top >= 0) {
                textBackgroundPaint.color = neonRed
                textBackgroundPaint.alpha = 240
                canvas.drawRoundRect(textBgRect, 10f, 10f, textBackgroundPaint)

                textPaint.color = Color.WHITE
                canvas.drawText(warningText, textBgRect.left + 18, textBgRect.bottom - 14, textPaint)
            }

            // خطوط تحذير قطرية حمراء
            val warningLinePaint = Paint().apply {
                color = neonRed
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                alpha = 120
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, warningLinePaint)
            canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, warningLinePaint)

            // توهج أحمر
            val glowPaint = Paint().apply {
                color = neonRed
                style = Paint.Style.STROKE
                strokeWidth = 18f
                alpha = 45
                isAntiAlias = true
            }
            canvas.drawRoundRect(rect, 18f, 18f, glowPaint)

            // أيقونة كاميرا صغيرة تشير إلى الالتقاط التلقائي
            val camPaint = Paint().apply {
                color = neonRed
                style = Paint.Style.FILL
                alpha = 200
            }
            canvas.drawCircle(rect.left + 20, rect.bottom - 20, 8f, camPaint)

        } catch (e: Throwable) {
            Log.e("OverlayView", "خطأ في drawUnknownFaceRed: ${e.message}", e)
        }
    }

    private fun drawCornersEnhanced(canvas: Canvas, rect: RectF, paint: Paint) {
        try {
            val cornerLength = 36f
            val cornerThickness = paint.strokeWidth

            // Top-left - زاوية سميكة
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

            // نقاط إضافية في الزوايا لجمال أكثر
            val dotPaint = Paint().apply {
                color = paint.color
                style = Paint.Style.FILL
            }
            canvas.drawCircle(rect.left, rect.top, 5f, dotPaint)
            canvas.drawCircle(rect.right, rect.top, 5f, dotPaint)
            canvas.drawCircle(rect.left, rect.bottom, 5f, dotPaint)
            canvas.drawCircle(rect.right, rect.bottom, 5f, dotPaint)

        } catch (e: Throwable) {
        }
    }
}
