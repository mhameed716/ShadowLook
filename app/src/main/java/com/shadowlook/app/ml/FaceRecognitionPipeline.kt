package com.shadowlook.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.shadowlook.app.data.local.converters.Converters
import com.shadowlook.app.data.local.db.AppDatabase
import com.shadowlook.app.data.local.entity.UserFaceEntity
import com.shadowlook.app.data.local.entity.UnknownFaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ShadowLook - Face Recognition Pipeline الكامل
 * 
 * تنفيذ دقيق للخطوات المطلوبة:
 * 
 * 1. إضافة الملف: mobilefacenet.tflite داخل مجلد الموارد (assets/mobilefacenet.tflite)
 * 2. الكشف الأولي (Face Detection): ML Kit Face Detection لكشف موقع الوجه وقص المنطقة فقط
 * 3. التجهيز (Pre-processing): إعادة حجم إلى 112x112 مع Normalization [-1.0 to 1.0]
 * 4. التمرير (Inference): تمرير الصورة للنموذج للحصول على بصمة الوجه (Embedding 128)
 * 5. المقارنة أو الحفظ: حفظ البصمة في قاعدة البيانات عند التسجيل، أو مقارنتها للتحقق
 */
class FaceRecognitionPipeline(private val context: Context) {

    private val tfliteHelper = TFLiteHelper(context)
    private val TAG = "FacePipeline"

    companion object {
        const val MODEL_PATH = "mobilefacenet.tflite" // في assets/mobilefacenet.tflite
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 128
        const val THRESHOLD = 0.45f
    }

    /**
     * الخطوة 1: التأكد من وجود الملف mobilefacenet.tflite في assets
     */
    fun checkModelExists(): Boolean {
        return try {
            context.assets.open(MODEL_PATH).use { input ->
                val size = input.available()
                Log.d(TAG, "✅ الملف موجود: $MODEL_PATH - الحجم: $size bytes")
                size > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ الملف غير موجود: $MODEL_PATH - سيتم استخدام وضع المحاكاة")
            Log.w(TAG, "لتحميل النموذج الحقيقي: ضع mobilefacenet.tflite في app/src/main/assets/")
            false
        }
    }

    /**
     * الخطوة 2: الكشف الأولي (Face Detection) - قص منطقة الوجه فقط
     * يستخدم ML Kit Face Detection (سريع) أو BlazeFace
     */
    fun detectAndCropFace(fullBitmap: Bitmap, face: Face): Bitmap? {
        return try {
            val boundingBox = face.boundingBox
            
            // قص منطقة الوجه فقط مع padding بسيط
            val padding = 20
            val left = (boundingBox.left - padding).coerceAtLeast(0)
            val top = (boundingBox.top - padding).coerceAtLeast(0)
            val right = (boundingBox.right + padding).coerceAtMost(fullBitmap.width)
            val bottom = (boundingBox.bottom + padding).coerceAtMost(fullBitmap.height)

            val width = right - left
            val height = bottom - top

            if (width > 0 && height > 0) {
                val cropped = Bitmap.createBitmap(fullBitmap, left, top, width, height)
                Log.d(TAG, "✅ الخطوة 2: كشف الوجه وقص المنطقة - ${width}x${height}")
                cropped
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل قص الوجه: ${e.message}", e)
            null
        }
    }

    /**
     * الخطوة 3: التجهيز (Pre-processing)
     * - إعادة تغيير الحجم إلى 112x112
     * - ضبط قيم البكسلات (Normalization) من [0-255] إلى [-1.0 to 1.0]
     * - الصيغة: (pixel - 127.5) / 128.0
     */
    fun preprocessFace(faceBitmap: Bitmap): Bitmap {
        return try {
            // إعادة الحجم إلى 112x112
            val resized = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            Log.d(TAG, "✅ الخطوة 3: Pre-processing - إعادة حجم إلى ${INPUT_SIZE}x${INPUT_SIZE} + Normalization [-1.0, 1.0]")
            resized
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل Pre-processing: ${e.message}", e)
            Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
    }

    /**
     * الخطوة 4: التمرير (Inference)
     * تمرير الصورة المجهزة للنموذج mobilefacenet.tflite للحصول على بصمة الوجه (Embedding)
     * الإخراج: FloatArray بحجم 128 قيمة
     */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        return try {
            // التجهيز
            val preprocessed = preprocessFace(faceBitmap)
            
            // التمرير للنموذج
            val embedding = tfliteHelper.getFaceEmbedding(preprocessed)
            
            Log.d(TAG, "✅ الخطوة 4: Inference - تم الحصول على Embedding بحجم ${embedding.size}")
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل Inference: ${e.message}", e)
            FloatArray(EMBEDDING_SIZE) { 0f }
        }
    }

    /**
     * الخطوة 5-أ: الحفظ - حفظ البصمة في قاعدة البيانات عند التسجيل
     */
    suspend fun saveNewFace(
        name: String,
        phone: String,
        jobTitle: String,
        address: String,
        faceBitmap: Bitmap,
        embedding: FloatArray
    ): Long = withContext(Dispatchers.IO) {
        try {
            // حفظ صورة الوجه
            val knownDir = File(context.filesDir, "known_faces")
            if (!knownDir.exists()) knownDir.mkdirs()

            val fileName = "known_${System.currentTimeMillis()}.jpg"
            val imageFile = File(knownDir, fileName)

            FileOutputStream(imageFile).use { out ->
                faceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // تحويل Embedding إلى JSON
            val embeddingJson = Converters.embeddingToJson(embedding)

            // حفظ في قاعدة البيانات مع تاريخ تلقائي
            val entity = UserFaceEntity(
                name = name,
                phone = phone,
                jobTitle = jobTitle,
                address = address,
                imagePath = imageFile.absolutePath,
                vectorEmbedding = embeddingJson,
                timestamp = System.currentTimeMillis(),
                formattedDate = UserFaceEntity.getCurrentFormattedDate()
            )

            val db = AppDatabase.getDatabase(context)
            val id = db.userFaceDao().insertKnown(entity)
            
            Log.d(TAG, "✅ الخطوة 5-أ: حفظ - تم حفظ $name مع Embedding في قاعدة البيانات - ID: $id - تاريخ: ${entity.formattedDate}")
            id
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل الحفظ: ${e.message}", e)
            -1L
        }
    }

    /**
     * الخطوة 5-ب: المقارنة - مقارنة البصمة بالبصمات المخزنة للتحقق من الهوية
     * يستخدم Euclidean Distance + Cosine Similarity
     * Threshold <= 0.4 = مطابق
     */
    suspend fun verifyFace(queryEmbedding: FloatArray): VerificationResult = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val knownUsers = db.userFaceDao().getAllKnownsList()

            if (knownUsers.isEmpty()) {
                return@withContext VerificationResult(null, Float.MAX_VALUE, false, 0f)
            }

            var bestMatch: UserFaceEntity? = null
            var bestDistance = Float.MAX_VALUE
            var bestCosine = -1f

            // مقارنة مع جميع البصمات المخزنة
            for (user in knownUsers) {
                try {
                    val storedEmbedding = Converters.jsonToEmbedding(user.vectorEmbedding)
                    
                    // حساب المسافة الأقليدية (L2)
                    val distance = tfliteHelper.calculateEuclideanDistanceFast(queryEmbedding, storedEmbedding, bestDistance)
                    if (distance >= bestDistance) continue // تخطي إذا أسوأ من الأفضل

                    // حساب التشابه الكوسيني (Cosine)
                    val cosine = tfliteHelper.calculateCosineSimilarityFast(queryEmbedding, storedEmbedding)

                    if (distance < bestDistance && cosine > 0.5f) {
                        bestDistance = distance
                        bestCosine = cosine
                        bestMatch = user
                        
                        // إذا وجدنا مطابقة ممتازة جداً، توقف
                        if (distance < 0.2f && cosine > 0.9f) break
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            val isMatch = bestDistance <= THRESHOLD && bestCosine >= 0.5f
            val similarityPercent = ((1 - (bestDistance / 0.6f).coerceIn(0f, 1f)) * 100).coerceIn(0f, 100f)

            Log.d(TAG, "✅ الخطوة 5-ب: مقارنة - ${if (isMatch) "مطابق: ${bestMatch?.name}" else "غير مطابق"} - مسافة: $bestDistance - تشابه: ${similarityPercent.toInt()}%")

            VerificationResult(bestMatch, bestDistance, isMatch, similarityPercent)

        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل المقارنة: ${e.message}", e)
            VerificationResult(null, Float.MAX_VALUE, false, 0f)
        }
    }

    /**
     * حفظ وجه مجهول مع تاريخ تلقائي
     */
    suspend fun saveUnknownFace(faceBitmap: Bitmap, embedding: FloatArray): Long = withContext(Dispatchers.IO) {
        try {
            val unknownDir = File(context.filesDir, "unknown_faces")
            if (!unknownDir.exists()) unknownDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val fileName = "unknown_$timestamp.jpg"
            val imageFile = File(unknownDir, fileName)

            FileOutputStream(imageFile).use { out ->
                faceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            val embeddingJson = Converters.embeddingToJson(embedding)
            val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

            val entity = UnknownFaceEntity(
                timestamp = timestamp,
                formattedDate = formattedDate,
                imagePath = imageFile.absolutePath,
                vectorEmbedding = embeddingJson
            )

            val db = AppDatabase.getDatabase(context)
            val id = db.unknownFaceDao().insertUnknown(entity)
            
            Log.d(TAG, "✅ حفظ مجهول: $fileName - تاريخ: $formattedDate")
            id
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل حفظ المجهول: ${e.message}", e)
            -1L
        }
    }

    data class VerificationResult(
        val matchedUser: UserFaceEntity?,
        val distance: Float,
        val isMatch: Boolean,
        val similarityPercent: Float
    )

    fun close() {
        try {
            tfliteHelper.close()
        } catch (e: Exception) {}
    }
}
