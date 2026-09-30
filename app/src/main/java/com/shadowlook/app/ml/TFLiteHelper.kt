package com.shadowlook.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class TFLiteHelper(private val context: Context) {

    private var interpreter: Any? = null // Use Any to avoid class loading crash if .so missing
    private val MODEL_NAME = "mobilefacenet.tflite"
    private val INPUT_SIZE = 112
    private val EMBEDDING_SIZE = 128
    private val MATCH_THRESHOLD = 0.4f
    private var isModelLoaded = false

    companion object {
        private const val TAG = "TFLiteHelper"
    }

    init {
        loadModelSafely()
    }

    private fun loadModelSafely() {
        try {
            // 1. Check if asset exists first to avoid FileNotFoundException
            val assetExists = try {
                context.assets.open(MODEL_NAME).close()
                true
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ ملف النموذج $MODEL_NAME غير موجود في assets/ - سيتم استخدام وضع المحاكاة")
                false
            }

            if (!assetExists) {
                Log.w(TAG, "📝 وضع المحاكاة نشط - التطبيق سيعمل بدون نموذج حقيقي")
                isModelLoaded = false
                interpreter = null
                return
            }

            // 2. Try to load TFLite Interpreter with full exception handling for .so files
            try {
                val modelFile = org.tensorflow.lite.support.common.FileUtil.loadMappedFile(context, MODEL_NAME)
                val options = org.tensorflow.lite.Interpreter.Options().apply {
                    setNumThreads(2) // Reduced threads to avoid OOM
                    // setUseNNAPI(false) // Disable NNAPI to avoid device compatibility issues
                }
                interpreter = org.tensorflow.lite.Interpreter(modelFile, options)
                isModelLoaded = true
                Log.d(TAG, "✅ تم تحميل نموذج MobileFaceNet بنجاح")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ فشل تحميل مكتبات TFLite الأصلية (.so files) - خطأ في المعمارية: ${e.message}", e)
                Log.e(TAG, "تأكد من تضمين armeabi-v7a, arm64-v8a في ndk.abiFilters")
                isModelLoaded = false
                interpreter = null
            } catch (e: Exception) {
                Log.e(TAG, "❌ فشل تحميل النموذج: ${e.message}", e)
                isModelLoaded = false
                interpreter = null
            }

        } catch (e: Throwable) {
            // Catch everything including Errors to prevent app crash
            Log.e(TAG, "❌ خطأ حرج في تهيئة TFLiteHelper: ${e.message}", e)
            isModelLoaded = false
            interpreter = null
        }
    }

    fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        // Always return safe result even if model not loaded
        if (!isModelLoaded || interpreter == null) {
            return generateDummyEmbedding(faceBitmap)
        }

        return try {
            val resizedBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)
            val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }
            (interpreter as? org.tensorflow.lite.Interpreter)?.run(inputBuffer, outputArray)
            outputArray[0]
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في توليد embedding: ${e.message}", e)
            generateDummyEmbedding(faceBitmap)
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        return try {
            val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            byteBuffer.order(ByteOrder.nativeOrder())
            val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
            bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16 and 0xFF)
                val g = (pixelValue shr 8 and 0xFF)
                val b = (pixelValue and 0xFF)
                byteBuffer.putFloat((r - 127.5f) / 128.0f)
                byteBuffer.putFloat((g - 127.5f) / 128.0f)
                byteBuffer.putFloat((b - 127.5f) / 128.0f)
            }
            byteBuffer
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في تحويل Bitmap: ${e.message}", e)
            ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply { order(ByteOrder.nativeOrder()) }
        }
    }

    fun calculateEuclideanDistance(embedding1: FloatArray, embedding2: FloatArray): Float {
        return try {
            if (embedding1.size != embedding2.size) return Float.MAX_VALUE
            var sum = 0f
            for (i in embedding1.indices) {
                val diff = embedding1[i] - embedding2[i]
                sum += diff * diff
            }
            sqrt(sum)
        } catch (e: Throwable) {
            Float.MAX_VALUE
        }
    }

    fun isMatch(embedding1: FloatArray, embedding2: FloatArray, threshold: Float = MATCH_THRESHOLD): Pair<Boolean, Float> {
        val distance = calculateEuclideanDistance(embedding1, embedding2)
        return Pair(distance <= threshold, distance)
    }

    fun findBestMatch(
        queryEmbedding: FloatArray,
        knownEmbeddings: List<Pair<Int, FloatArray>>
    ): Triple<Int?, Float, Boolean> {
        return try {
            var bestId: Int? = null
            var bestDistance = Float.MAX_VALUE
            for ((userId, embedding) in knownEmbeddings) {
                val distance = calculateEuclideanDistance(queryEmbedding, embedding)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestId = userId
                }
            }
            val isMatch = bestDistance <= MATCH_THRESHOLD
            Triple(if (isMatch) bestId else null, bestDistance, isMatch)
        } catch (e: Throwable) {
            Triple(null, Float.MAX_VALUE, false)
        }
    }

    private fun generateDummyEmbedding(bitmap: Bitmap): FloatArray {
        return try {
            val hash = try {
                bitmap.width + bitmap.height + (bitmap.getPixel(0, 0) and 0xFF)
            } catch (e: Exception) {
                System.currentTimeMillis().toInt() % 1000
            }
            FloatArray(EMBEDDING_SIZE) { i ->
                kotlin.math.sin((hash + i).toFloat() * 0.1f) * 0.5f
            }
        } catch (e: Throwable) {
            FloatArray(EMBEDDING_SIZE) { 0f }
        }
    }

    fun close() {
        try {
            (interpreter as? org.tensorflow.lite.Interpreter)?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في إغلاق Interpreter: ${e.message}")
        } finally {
            interpreter = null
            isModelLoaded = false
        }
    }

    fun isModelReady(): Boolean = isModelLoaded
}
