package com.shadowlook.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class TFLiteHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val MODEL_NAME = "mobilefacenet.tflite"
    private val INPUT_SIZE = 112
    private val EMBEDDING_SIZE = 128
    private val MATCH_THRESHOLD = 0.4f

    companion object {
        private const val TAG = "TFLiteHelper"
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelFile = FileUtil.loadMappedFile(context, MODEL_NAME)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            interpreter = Interpreter(modelFile, options)
            Log.d(TAG, "✅ تم تحميل نموذج MobileFaceNet بنجاح - حجم الإدخال: ${INPUT_SIZE}x${INPUT_SIZE}, الإخراج: $EMBEDDING_SIZE")
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل النموذج: ${e.message}. تأكد من وجود mobilefacenet.tflite في assets/", e)
            // Fallback: إنشاء interpreter وهمي للاختبار بدون النموذج
            interpreter = null
        }
    }

    /**
     * تحويل صورة الوجه المقصوصة إلى embedding vector بحجم 128
     * - Resize إلى 112x112
     * - Normalize pixels من [-1.0 to 1.0]
     */
    fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        if (interpreter == null) {
            Log.w(TAG, "النموذج غير محمل، إرجاع embedding وهمي للاختبار")
            // إرجاع embedding وهمي للاختبار - في الإنتاج يجب وجود النموذج
            return generateDummyEmbedding(faceBitmap)
        }

        return try {
            // 1. Resize إلى 112x112
            val resizedBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)

            // 2. تحويل إلى ByteBuffer مع Normalize [-1, 1]
            val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

            // 3. إعداد output buffer
            val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }

            // 4. تشغيل الاستدلال
            interpreter?.run(inputBuffer, outputArray)

            val embedding = outputArray[0]
            Log.d(TAG, "تم توليد embedding: حجم ${embedding.size}, أول 3 قيم: ${embedding.take(3)}")
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في توليد embedding: ${e.message}", e)
            FloatArray(EMBEDDING_SIZE) { 0f }
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixelValue in intValues) {
            // استخراج RGB
            val r = (pixelValue shr 16 and 0xFF)
            val g = (pixelValue shr 8 and 0xFF)
            val b = (pixelValue and 0xFF)

            // Normalize إلى [-1, 1]: (pixel - 127.5) / 128.0
            byteBuffer.putFloat((r - 127.5f) / 128.0f)
            byteBuffer.putFloat((g - 127.5f) / 128.0f)
            byteBuffer.putFloat((b - 127.5f) / 128.0f)
        }
        return byteBuffer
    }

    /**
     * حساب المسافة الإقليدية بين embedding وجهين
     * Distance = sqrt( sum( (A[i] - B[i])^2 ) )
     */
    fun calculateEuclideanDistance(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) {
            Log.e(TAG, "أحجام embedding غير متطابقة: ${embedding1.size} vs ${embedding2.size}")
            return Float.MAX_VALUE
        }
        var sum = 0f
        for (i in embedding1.indices) {
            val diff = embedding1[i] - embedding2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /**
     * مطابقة وجه مع قاعدة البيانات
     * @return Pair<isMatch, distance>
     */
    fun isMatch(embedding1: FloatArray, embedding2: FloatArray, threshold: Float = MATCH_THRESHOLD): Pair<Boolean, Float> {
        val distance = calculateEuclideanDistance(embedding1, embedding2)
        val isMatch = distance <= threshold
        Log.d(TAG, "المسافة: $distance, العتبة: $threshold, مطابقة: $isMatch")
        return Pair(isMatch, distance)
    }

    /**
     * البحث عن أفضل مطابقة في قاعدة البيانات
     */
    fun findBestMatch(
        queryEmbedding: FloatArray,
        knownEmbeddings: List<Pair<Int, FloatArray>> // Pair<userId, embedding>
    ): Triple<Int?, Float, Boolean> {
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
        return Triple(if (isMatch) bestId else null, bestDistance, isMatch)
    }

    // دالة وهمية للاختبار عند عدم وجود النموذج
    private fun generateDummyEmbedding(bitmap: Bitmap): FloatArray {
        // توليد embedding يعتمد على محتوى الصورة بشكل بسيط للاختبار
        val hash = bitmap.width + bitmap.height + (bitmap.getPixel(0, 0) and 0xFF)
        return FloatArray(EMBEDDING_SIZE) { i ->
            kotlin.math.sin((hash + i).toFloat() * 0.1f) * 0.5f
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
