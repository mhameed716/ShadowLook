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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ShadowLook v2.5 - استراتيجية جديدة: التقاط أولاً ثم البحث
 * 1. أول ما يظهر وجه على الشاشة → يلتقط له صورة فوراً
 * 2. ثم يبحث عنها في قاعدة بيانات الوجوه المعروفة
 * 3. إذا لم يجدها → يضيفها إلى الوجوه غير المعروفة تلقائياً مع تاريخ
 * + جميع الميزات السابقة: رؤية ليلية، كاميرتين، 100x أسرع، 3D، تتبع، 3م
 */
class FaceAnalyzer(
    private val context: Context,
    private val overlayView: OverlayView,
    private val tfliteHelper: TFLiteHelper,
    private val onFaceRecognized: (FaceRecognitionResult) -> Unit,
    private val onUnknownFaceDetected: (Bitmap, FloatArray, List<UnknownSimilarity>) -> Unit,
    private val onNoFaceDetected: () -> Unit = {}
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"
        private const val UNKNOWN_CAPTURE_COOLDOWN_MS = 1500L
    }

    data class UnknownSimilarity(
        val entity: UnknownFaceEntity,
        val similarity: Float,
        val distance: Float
    )

    private val detector by lazy {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .setMinFaceSize(0.05f) // 3م+
                .build()
            FaceDetection.getClient(options)
        } catch (e: Throwable) {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.05f)
                .build()
            FaceDetection.getClient(options)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUnknownCaptureTime = 0L
    private var knownEmbeddingsCache: List<Pair<Int, FloatArray>> = emptyList()
    private var unknownEmbeddingsCache: List<Pair<UnknownFaceEntity, FloatArray>> = emptyList()
    private var lastCacheUpdateTime = 0L
    private val CACHE_VALIDITY_MS = 10000L
    private var isNightVisionMode = false

    fun setNightVisionMode(enabled: Boolean) {
        isNightVisionMode = enabled
        Log.d(TAG, "🌙 Night Vision: $enabled")
    }

    data class FaceRecognitionResult(
        val face: Face?,
        val boundingBox: Rect,
        val trackingId: Int?,
        val userId: Int?,
        val userName: String?,
        val jobTitle: String?,
        val phone: String?,
        val address: String?,
        val imagePath: String?,
        val distance: Float,
        val cosineSimilarity: Float,
        val similarityPercent: Float,
        val quality: Float,
        val isKnown: Boolean,
        val faceBitmap: Bitmap?,
        val headEulerX: Float = 0f,
        val headEulerY: Float = 0f,
        val headEulerZ: Float = 0f,
        val isSmiling: Boolean = false,
        val leftEyeOpen: Boolean = true,
        val rightEyeOpen: Boolean = true
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    try {
                        if (faces.isNotEmpty()) {
                            processFacesNewStrategy(faces, mediaImage, imageProxy)
                        } else {
                            try {
                                overlayView.setResults(emptyList())
                                onNoFaceDetected()
                            } catch (e: Throwable) {}
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ: ${e.message}", e)
                    }
                }
                .addOnFailureListener { e ->
                    try {
                        overlayView.setResults(emptyList())
                    } catch (e2: Throwable) {}
                }
                .addOnCompleteListener {
                    try {
                        imageProxy.close()
                    } catch (e: Throwable) {}
                }
        } catch (e: Throwable) {
            try {
                imageProxy.close()
            } catch (e2: Throwable) {}
        }
    }

    /**
     * استراتيجية جديدة v2.5:
     * 1. أول ما يظهر وجه → التقاط صورة فوراً
     * 2. ثم البحث في قاعدة المعروفين
     * 3. إذا لم يوجد → إضافة إلى المجهولين مع تاريخ تلقائي
     */
    private fun processFacesNewStrategy(faces: List<Face>, mediaImage: Image, imageProxy: ImageProxy) {
        scope.launch {
            try {
                updateCachesIfNeeded()

                val fullBitmap = try {
                    mediaImage.toBitmapSafe(imageProxy.imageInfo.rotationDegrees)
                } catch (e: Throwable) {
                    return@launch
                } ?: return@launch

                // تحسين ليلي للصورة الكاملة
                val enhancedFullBitmap = if (isNightVisionMode) {
                    try {
                        tfliteHelper.enhanceForNightVision(fullBitmap)
                    } catch (e: Throwable) {
                        fullBitmap
                    }
                } else {
                    fullBitmap
                }

                val results = mutableListOf<FaceRecognitionResult>()

                for (face in faces) {
                    try {
                        val boundingBox = face.boundingBox
                        
                        // === الخطوة 1: التقاط صورة الوجه فوراً أول ما يظهر ===
                        var capturedFaceBitmap = cropFaceSafe(enhancedFullBitmap, boundingBox) ?: continue
                        
                        // تحسين ليلي للوجه الملتقط
                        val qualityCheck = try {
                            tfliteHelper.checkFaceQuality(capturedFaceBitmap)
                        } catch (e: Throwable) {
                            1f
                        }

                        if (isNightVisionMode || qualityCheck < 0.5f) {
                            try {
                                capturedFaceBitmap = tfliteHelper.enhanceForNightVision(capturedFaceBitmap)
                            } catch (e: Throwable) {}
                        }

                        if (qualityCheck < 0.20f && !isNightVisionMode) continue

                        // حفظ نسخة من الصورة الملتقطة للاستخدام في حالة المجهول
                        val capturedBitmapForUnknown = capturedFaceBitmap.copy(capturedFaceBitmap.config ?: Bitmap.Config.ARGB_8888, false)

                        // === الخطوة 2: البحث في قاعدة بيانات الوجوه المعروفة ===
                        val embedding = try {
                            tfliteHelper.getFaceEmbedding(capturedFaceBitmap)
                        } catch (e: Throwable) {
                            FloatArray(128) { 0f }
                        }

                        // مطابقة 100x أسرع
                        val (matchedId, distance, isMatch) = try {
                            tfliteHelper.findBestMatch(embedding, knownEmbeddingsCache)
                        } catch (e: Throwable) {
                            Triple(null, Float.MAX_VALUE, false)
                        }

                        val cosine = try {
                            if (matchedId != null) {
                                val matchedEmbedding = knownEmbeddingsCache.find { it.first == matchedId }?.second
                                if (matchedEmbedding != null) {
                                    tfliteHelper.calculateCosineSimilarityFast(embedding, matchedEmbedding)
                                } else 0f
                            } else 0f
                        } catch (e: Throwable) {
                            0f
                        }

                        val similarityPercent = try {
                            val euclideanSimilarity = ((1 - (distance / 0.6f).coerceIn(0f, 1f)) * 100).coerceIn(0f, 100f)
                            val cosineSimilarity = ((cosine + 1) / 2 * 100).coerceIn(0f, 100f)
                            (euclideanSimilarity * 0.6f + cosineSimilarity * 0.4f)
                        } catch (e: Throwable) {
                            0f
                        }

                        val headX = try { face.headEulerAngleX } catch (e: Throwable) { 0f }
                        val headY = try { face.headEulerAngleY } catch (e: Throwable) { 0f }
                        val headZ = try { face.headEulerAngleZ } catch (e: Throwable) { 0f }
                        val smiling = try { (face.smilingProbability ?: 0f) > 0.5f } catch (e: Throwable) { false }
                        val leftEye = try { (face.leftEyeOpenProbability ?: 1f) > 0.5f } catch (e: Throwable) { true }
                        val rightEye = try { (face.rightEyeOpenProbability ?: 1f) > 0.5f } catch (e: Throwable) { true }
                        val trackingId = try { face.trackingId } catch (e: Throwable) { null }

                        if (isMatch && matchedId != null) {
                            // === معروف: وجد في قاعدة المعروفين ===
                            val userEntity = getUserById(matchedId)
                            val result = FaceRecognitionResult(
                                face = face,
                                boundingBox = boundingBox,
                                trackingId = trackingId,
                                userId = matchedId,
                                userName = userEntity?.name ?: "مستخدم معروف",
                                jobTitle = userEntity?.jobTitle,
                                phone = userEntity?.phone,
                                address = userEntity?.address,
                                imagePath = userEntity?.imagePath,
                                distance = distance,
                                cosineSimilarity = cosine,
                                similarityPercent = similarityPercent,
                                quality = qualityCheck,
                                isKnown = true,
                                faceBitmap = capturedFaceBitmap,
                                headEulerX = headX,
                                headEulerY = headY,
                                headEulerZ = headZ,
                                isSmiling = smiling,
                                leftEyeOpen = leftEye,
                                rightEyeOpen = rightEye
                            )
                            results.add(result)
                            withContext(Dispatchers.Main) {
                                try {
                                    onFaceRecognized(result)
                                } catch (e: Throwable) {}
                            }
                            Log.d(TAG, "✅ معروف: ${userEntity?.name} - ${similarityPercent.toInt()}% - تم التقاط صورة أولاً ثم البحث")
                        } else {
                            // === مجهول: لم يوجد في قاعدة المعروفين → إضافة إلى المجهولين ===
                            val currentTime = System.currentTimeMillis()
                            val timeSinceLastCapture = currentTime - lastUnknownCaptureTime

                            val result = FaceRecognitionResult(
                                face = face,
                                boundingBox = boundingBox,
                                trackingId = trackingId,
                                userId = null,
                                userName = null,
                                jobTitle = null,
                                phone = null,
                                address = null,
                                imagePath = null,
                                distance = distance,
                                cosineSimilarity = cosine,
                                similarityPercent = similarityPercent,
                                quality = qualityCheck,
                                isKnown = false,
                                faceBitmap = capturedFaceBitmap,
                                headEulerX = headX,
                                headEulerY = headY,
                                headEulerZ = headZ,
                                isSmiling = smiling,
                                leftEyeOpen = leftEye,
                                rightEyeOpen = rightEye
                            )
                            results.add(result)

                            withContext(Dispatchers.Main) {
                                try {
                                    onFaceRecognized(result)
                                } catch (e: Throwable) {}
                            }

                            // البحث في المجهولين المشابهين
                            val similarUnknowns = try {
                                findSimilarUnknownsFast(embedding)
                            } catch (e: Throwable) {
                                emptyList()
                            }

                            // === الخطوة 3: إذا مجهول، أضف إلى قاعدة المجهولين مع تاريخ تلقائي ===
                            if (timeSinceLastCapture >= UNKNOWN_CAPTURE_COOLDOWN_MS) {
                                lastUnknownCaptureTime = currentTime
                                launch {
                                    try {
                                        // حفظ الصورة الملتقطة فوراً مع تاريخ تلقائي
                                        saveUnknownFace(capturedBitmapForUnknown, embedding)
                                        updateUnknownCache()
                                        Log.d(TAG, "📸 مجهول: تم التقاط صورة أولاً ثم البحث - لم يوجد في المعروفين → أضيف إلى المجهولين مع تاريخ ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "فشل حفظ المجهول: ${e.message}", e)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    try {
                                        onUnknownFaceDetected(capturedBitmapForUnknown, embedding, similarUnknowns)
                                    } catch (e: Throwable) {}
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    try {
                                        onUnknownFaceDetected(capturedBitmapForUnknown, embedding, similarUnknowns)
                                    } catch (e: Throwable) {}
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في وجه: ${e.message}", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    try {
                        overlayView.setResults(results)
                    } catch (e: Throwable) {}
                }

            } catch (e: Throwable) {
                Log.e(TAG, "خطأ: ${e.message}", e)
            }
        }
    }

    private suspend fun updateCachesIfNeeded() {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCacheUpdateTime > CACHE_VALIDITY_MS || knownEmbeddingsCache.isEmpty()) {
                val db = AppDatabase.getDatabase(context)
                val knownUsers = db.userFaceDao().getAllKnownsList()
                knownEmbeddingsCache = knownUsers.mapNotNull { user ->
                    try {
                        val embedding = Converters.jsonToEmbedding(user.vectorEmbedding)
                        Pair(user.id, embedding)
                    } catch (e: Throwable) {
                        null
                    }
                }
                
                updateUnknownCache()
                
                lastCacheUpdateTime = currentTime
                Log.d(TAG, "كاش: معروف=${knownEmbeddingsCache.size}, مجهول=${unknownEmbeddingsCache.size}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ كاش: ${e.message}", e)
        }
    }

    private suspend fun updateUnknownCache() {
        try {
            val db = AppDatabase.getDatabase(context)
            val unknownUsers = db.unknownFaceDao().getAllUnknownsList()
            unknownEmbeddingsCache = unknownUsers.mapNotNull { user ->
                try {
                    val embedding = Converters.jsonToEmbedding(user.vectorEmbedding)
                    Pair(user, embedding)
                } catch (e: Throwable) {
                    null
                }
            }
        } catch (e: Throwable) {}
    }

    private fun findSimilarUnknownsFast(queryEmbedding: FloatArray): List<UnknownSimilarity> {
        return try {
            val similarities = mutableListOf<UnknownSimilarity>()
            
            for ((entity, embedding) in unknownEmbeddingsCache) {
                try {
                    val distance = tfliteHelper.calculateEuclideanDistanceFast(queryEmbedding, embedding, 0.6f)
                    if (distance > 0.6f) continue
                    
                    val similarity = ((1 - (distance / 0.6f).coerceIn(0f, 1f)) * 100).coerceIn(0f, 100f)
                    
                    if (similarity > 60f) {
                        similarities.add(UnknownSimilarity(entity, similarity, distance))
                        if (similarities.size >= 10) break
                    }
                } catch (e: Throwable) {}
            }
            
            similarities.sortedByDescending { it.similarity }.take(10)
            
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private suspend fun getUserById(userId: Int) = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.userFaceDao().getKnownById(userId)
        } catch (e: Throwable) {
            null
        }
    }

    private suspend fun saveUnknownFace(faceBitmap: Bitmap, embedding: FloatArray) = withContext(Dispatchers.IO) {
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
            val unknownEntity = UnknownFaceEntity(
                timestamp = timestamp,
                formattedDate = formattedDate,
                imagePath = imageFile.absolutePath,
                vectorEmbedding = embeddingJson
            )

            val db = AppDatabase.getDatabase(context)
            db.unknownFaceDao().insertUnknown(unknownEntity)

            Log.d(TAG, "✅ حفظ مجهول مع تاريخ تلقائي: $fileName - $formattedDate")

        } catch (e: Throwable) {
            Log.e(TAG, "❌ فشل حفظ المجهول: ${e.message}", e)
        }
    }

    private fun cropFaceSafe(fullBitmap: Bitmap, boundingBox: Rect): Bitmap? {
        return try {
            val padding = 20
            val left = (boundingBox.left - padding).coerceAtLeast(0)
            val top = (boundingBox.top - padding).coerceAtLeast(0)
            val right = (boundingBox.right + padding).coerceAtMost(fullBitmap.width)
            val bottom = (boundingBox.bottom + padding).coerceAtMost(fullBitmap.height)

            val width = right - left
            val height = bottom - top

            if (width > 0 && height > 0 && width <= fullBitmap.width && height <= fullBitmap.height) {
                Bitmap.createBitmap(fullBitmap, left, top, width, height)
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun Image.toBitmapSafe(rotationDegrees: Int): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val imageBytes = out.toByteArray()
            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            if (bitmap == null) {
                return toBitmapFallback(rotationDegrees)
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            bitmap
        } catch (e: Throwable) {
            try {
                toBitmapFallback(rotationDegrees)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun Image.toBitmapFallback(rotationDegrees: Int): Bitmap? {
        return try {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            bitmap
        } catch (e: Throwable) {
            null
        }
    }

    fun close() {
        try {
            scope.cancel()
            detector.close()
        } catch (e: Throwable) {}
    }
}
