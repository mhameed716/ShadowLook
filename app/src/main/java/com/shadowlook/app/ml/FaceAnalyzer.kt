package com.shadowlook.app.ml

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shadowlook.app.data.local.converters.Converters
import com.shadowlook.app.data.local.db.AppDatabase
import com.shadowlook.app.data.local.entity.UnknownFaceEntity
import com.shadowlook.app.ui.view.OverlayView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class FaceAnalyzer(
    private val context: Context,
    private val overlayView: OverlayView,
    private val tfliteHelper: TFLiteHelper,
    private val onFaceRecognized: (FaceRecognitionResult) -> Unit,
    private val onUnknownFaceDetected: (Bitmap, FloatArray) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"
        private const val UNKNOWN_CAPTURE_COOLDOWN_MS = 5000L // 5 ثواني منع التكرار
    }

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUnknownCaptureTime = 0L
    private var knownEmbeddingsCache: List<Pair<Int, FloatArray>> = emptyList()
    private var lastCacheUpdateTime = 0L
    private val CACHE_VALIDITY_MS = 10000L // تحديث الكاش كل 10 ثواني

    data class FaceRecognitionResult(
        val face: Face,
        val boundingBox: Rect,
        val userId: Int?,
        val userName: String?,
        val jobTitle: String?,
        val phone: String?,
        val address: String?,
        val imagePath: String?,
        val distance: Float,
        val isKnown: Boolean,
        val faceBitmap: Bitmap?
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    processFaces(faces, mediaImage, imageProxy)
                } else {
                    overlayView.setResults(emptyList())
                    onFaceRecognized(
                        FaceRecognitionResult(
                            face = null as Face? ?: return@addOnSuccessListener,
                            boundingBox = Rect(),
                            userId = null,
                            userName = null,
                            jobTitle = null,
                            phone = null,
                            address = null,
                            imagePath = null,
                            distance = 0f,
                            isKnown = false,
                            faceBitmap = null
                        )
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "فشل كشف الوجه: ${e.message}", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun processFaces(faces: List<Face>, mediaImage: Image, imageProxy: ImageProxy) {
        scope.launch {
            // تحديث كاش الـ embeddings إذا لزم
            updateKnownEmbeddingsCacheIfNeeded()

            val fullBitmap = mediaImage.toBitmap(imageProxy.imageInfo.rotationDegrees)
            val results = mutableListOf<FaceRecognitionResult>()

            for (face in faces) {
                val boundingBox = face.boundingBox

                // قص الوجه من الصورة الكاملة
                val faceBitmap = cropFace(fullBitmap, boundingBox)

                if (faceBitmap != null) {
                    // توليد embedding للوجه
                    val embedding = tfliteHelper.getFaceEmbedding(faceBitmap)

                    // البحث عن مطابقة في قاعدة البيانات
                    val (matchedId, distance, isMatch) = tfliteHelper.findBestMatch(
                        embedding,
                        knownEmbeddingsCache
                    )

                    if (isMatch && matchedId != null) {
                        // وجه معروف
                        val userEntity = getUserById(matchedId)
                        val result = FaceRecognitionResult(
                            face = face,
                            boundingBox = boundingBox,
                            userId = matchedId,
                            userName = userEntity?.name ?: "مستخدم معروف",
                            jobTitle = userEntity?.jobTitle,
                            phone = userEntity?.phone,
                            address = userEntity?.address,
                            imagePath = userEntity?.imagePath,
                            distance = distance,
                            isKnown = true,
                            faceBitmap = faceBitmap
                        )
                        results.add(result)
                        withContext(Dispatchers.Main) {
                            onFaceRecognized(result)
                        }
                    } else {
                        // وجه مجهول - منطق الالتقاط التلقائي مع Cooldown 5 ثواني
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastCapture = currentTime - lastUnknownCaptureTime

                        val result = FaceRecognitionResult(
                            face = face,
                            boundingBox = boundingBox,
                            userId = null,
                            userName = null,
                            jobTitle = null,
                            phone = null,
                            address = null,
                            imagePath = null,
                            distance = distance,
                            isKnown = false,
                            faceBitmap = faceBitmap
                        )
                        results.add(result)

                        withContext(Dispatchers.Main) {
                            onFaceRecognized(result)
                        }

                        // منطق الحفظ التلقائي للمجهولين
                        if (timeSinceLastCapture >= UNKNOWN_CAPTURE_COOLDOWN_MS) {
                            lastUnknownCaptureTime = currentTime
                            Log.d(TAG, "⏱️ تم تفعيل Cooldown - حفظ وجه مجهول جديد")

                            // حفظ في الخلفية
                            launch {
                                saveUnknownFace(faceBitmap, embedding)
                            }

                            withContext(Dispatchers.Main) {
                                onUnknownFaceDetected(faceBitmap, embedding)
                            }
                        } else {
                            Log.d(TAG, "⏳ Cooldown نشط - متبقي ${UNKNOWN_CAPTURE_COOLDOWN_MS - timeSinceLastCapture}ms")
                        }
                    }
                }
            }

            // تحديث OverlayView بالمربعات
            withContext(Dispatchers.Main) {
                overlayView.setResults(results)
            }
        }
    }

    private suspend fun updateKnownEmbeddingsCacheIfNeeded() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCacheUpdateTime > CACHE_VALIDITY_MS || knownEmbeddingsCache.isEmpty()) {
            try {
                val db = AppDatabase.getDatabase(context)
                val knownUsers = db.userFaceDao().getAllKnownsList()
                knownEmbeddingsCache = knownUsers.mapNotNull { user ->
                    try {
                        val embedding = Converters.jsonToEmbedding(user.vectorEmbedding)
                        Pair(user.id, embedding)
                    } catch (e: Exception) {
                        Log.e(TAG, "خطأ في تحويل embedding للمستخدم ${user.id}: ${e.message}")
                        null
                    }
                }
                lastCacheUpdateTime = currentTime
                Log.d(TAG, "تم تحديث كاش الـ embeddings: ${knownEmbeddingsCache.size} مستخدم")
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في تحديث الكاش: ${e.message}", e)
            }
        }
    }

    private suspend fun getUserById(userId: Int) = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.userFaceDao().getKnownById(userId)
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في جلب المستخدم $userId: ${e.message}")
            null
        }
    }

    private suspend fun saveUnknownFace(faceBitmap: Bitmap, embedding: FloatArray) = withContext(Dispatchers.IO) {
        try {
            // 1. حفظ الصورة في /unknown_faces/
            val unknownDir = File(context.filesDir, "unknown_faces")
            if (!unknownDir.exists()) unknownDir.mkdirs()

            val timestamp = System.currentTimeMillis()
            val fileName = "unknown_$timestamp.jpg"
            val imageFile = File(unknownDir, fileName)

            FileOutputStream(imageFile).use { out ->
                faceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // 2. حفظ في Room DB
            val embeddingJson = Converters.embeddingToJson(embedding)
            val unknownEntity = UnknownFaceEntity(
                timestamp = timestamp,
                formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)),
                imagePath = imageFile.absolutePath,
                vectorEmbedding = embeddingJson
            )

            val db = AppDatabase.getDatabase(context)
            db.unknownFaceDao().insertUnknown(unknownEntity)

            Log.d(TAG, "✅ تم حفظ وجه مجهول: $fileName")

        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل حفظ الوجه المجهول: ${e.message}", e)
        }
    }

    private fun cropFace(fullBitmap: Bitmap, boundingBox: Rect): Bitmap? {
        return try {
            // توسيع المربع قليلاً ليشمل كامل الوجه
            val padding = 20
            val left = (boundingBox.left - padding).coerceAtLeast(0)
            val top = (boundingBox.top - padding).coerceAtLeast(0)
            val right = (boundingBox.right + padding).coerceAtMost(fullBitmap.width)
            val bottom = (boundingBox.bottom + padding).coerceAtMost(fullBitmap.height)

            val width = right - left
            val height = bottom - top

            if (width > 0 && height > 0) {
                Bitmap.createBitmap(fullBitmap, left, top, width, height)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في قص الوجه: ${e.message}")
            null
        }
    }

    // تحويل Image إلى Bitmap
    private fun Image.toBitmap(rotationDegrees: Int): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // تطبيق الدوران
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    fun close() {
        scope.cancel()
        detector.close()
    }
}
