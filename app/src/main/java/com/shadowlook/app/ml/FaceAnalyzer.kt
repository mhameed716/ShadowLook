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

class FaceAnalyzer(
    private val context: Context,
    private val overlayView: OverlayView,
    private val tfliteHelper: TFLiteHelper,
    private val onFaceRecognized: (FaceRecognitionResult) -> Unit,
    private val onUnknownFaceDetected: (Bitmap, FloatArray) -> Unit,
    private val onNoFaceDetected: () -> Unit = {}
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceAnalyzer"
        private const val UNKNOWN_CAPTURE_COOLDOWN_MS = 3000L // تقليل من 5 ثواني إلى 3 لسرعة أعلى
        private const val FRAME_SKIP = 2 // تحسين السرعة: معالجة كل ثالث إطار فقط
    }

    private val detector by lazy {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST) // أسرع وضع
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE) // لا نحتاج Landmarks لسرعة أعلى
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking() // تفعيل التتبع لسرعة أعلى بين الإطارات
                .build()
            FaceDetection.getClient(options)
        } catch (e: Throwable) {
            Log.e(TAG, "فشل إنشاء detector: ${e.message}", e)
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            FaceDetection.getClient(options)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUnknownCaptureTime = 0L
    private var knownEmbeddingsCache: List<Pair<Int, FloatArray>> = emptyList()
    private var lastCacheUpdateTime = 0L
    private val CACHE_VALIDITY_MS = 15000L // زيادة مدة الكاش لسرعة أعلى
    private var frameCount = 0
    private var lastProcessTime = 0L

    data class FaceRecognitionResult(
        val face: Face?,
        val boundingBox: Rect,
        val userId: Int?,
        val userName: String?,
        val jobTitle: String?,
        val phone: String?,
        val address: String?,
        val imagePath: String?,
        val distance: Float,
        val cosineSimilarity: Float = 0f,
        val quality: Float = 1f,
        val isKnown: Boolean,
        val faceBitmap: Bitmap?
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        // تحسين السرعة: تخطي بعض الإطارات
        frameCount++
        if (frameCount % (FRAME_SKIP + 1) != 0) {
            imageProxy.close()
            return
        }

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
                            processFaces(faces, mediaImage, imageProxy)
                        } else {
                            try {
                                overlayView.setResults(emptyList())
                                onNoFaceDetected()
                            } catch (e: Throwable) {
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في معالجة النتائج: ${e.message}", e)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "فشل كشف الوجه: ${e.message}", e)
                    try {
                        overlayView.setResults(emptyList())
                    } catch (e2: Throwable) {
                    }
                }
                .addOnCompleteListener {
                    try {
                        imageProxy.close()
                    } catch (e: Throwable) {
                    }
                }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في analyze: ${e.message}", e)
            try {
                imageProxy.close()
            } catch (e2: Throwable) {
            }
        }
    }

    private fun processFaces(faces: List<Face>, mediaImage: Image, imageProxy: ImageProxy) {
        scope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                updateKnownEmbeddingsCacheIfNeeded()

                val fullBitmap = try {
                    mediaImage.toBitmapSafe(imageProxy.imageInfo.rotationDegrees)
                } catch (e: Throwable) {
                    Log.e(TAG, "فشل تحويل Image: ${e.message}", e)
                    return@launch
                } ?: return@launch

                val results = mutableListOf<FaceRecognitionResult>()

                for (face in faces) {
                    try {
                        val boundingBox = face.boundingBox
                        val faceBitmap = cropFaceSafe(fullBitmap, boundingBox) ?: continue

                        // تحسين الدقة: فحص جودة الوجه
                        val quality = try {
                            tfliteHelper.checkFaceQuality(faceBitmap)
                        } catch (e: Throwable) {
                            1f
                        }

                        // إذا الجودة منخفضة جداً، تخطى
                        if (quality < 0.3f) {
                            Log.w(TAG, "جودة وجه منخفضة: $quality - تخطي")
                            continue
                        }

                        val embedding = try {
                            tfliteHelper.getFaceEmbedding(faceBitmap)
                        } catch (e: Throwable) {
                            FloatArray(128) { 0f }
                        }

                        val (matchedId, distance, isMatch) = try {
                            tfliteHelper.findBestMatch(embedding, knownEmbeddingsCache)
                        } catch (e: Throwable) {
                            Triple(null, Float.MAX_VALUE, false)
                        }

                        // تحسين الدقة: حساب Cosine Similarity أيضاً
                        val cosine = try {
                            if (matchedId != null) {
                                val matchedEmbedding = knownEmbeddingsCache.find { it.first == matchedId }?.second
                                if (matchedEmbedding != null) {
                                    tfliteHelper.calculateCosineSimilarity(embedding, matchedEmbedding)
                                } else 0f
                            } else 0f
                        } catch (e: Throwable) {
                            0f
                        }

                        if (isMatch && matchedId != null) {
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
                                cosineSimilarity = cosine,
                                quality = quality,
                                isKnown = true,
                                faceBitmap = faceBitmap
                            )
                            results.add(result)
                            withContext(Dispatchers.Main) {
                                try {
                                    onFaceRecognized(result)
                                } catch (e: Throwable) {
                                }
                            }
                        } else {
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
                                cosineSimilarity = cosine,
                                quality = quality,
                                isKnown = false,
                                faceBitmap = faceBitmap
                            )
                            results.add(result)

                            withContext(Dispatchers.Main) {
                                try {
                                    onFaceRecognized(result)
                                } catch (e: Throwable) {
                                }
                            }

                            if (timeSinceLastCapture >= UNKNOWN_CAPTURE_COOLDOWN_MS) {
                                lastUnknownCaptureTime = currentTime
                                launch {
                                    try {
                                        saveUnknownFace(faceBitmap, embedding)
                                    } catch (e: Throwable) {
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    try {
                                        onUnknownFaceDetected(faceBitmap, embedding)
                                    } catch (e: Throwable) {
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في وجه واحد: ${e.message}", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    try {
                        overlayView.setResults(results)
                    } catch (e: Throwable) {
                    }
                }

                val processTime = System.currentTimeMillis() - startTime
                if (processTime > 100) {
                    Log.d(TAG, "⏱️ وقت المعالجة: ${processTime}ms لـ ${faces.size} وجوه")
                }
                lastProcessTime = processTime

            } catch (e: Throwable) {
                Log.e(TAG, "خطأ في processFaces: ${e.message}", e)
            }
        }
    }

    private suspend fun updateKnownEmbeddingsCacheIfNeeded() {
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
                lastCacheUpdateTime = currentTime
                Log.d(TAG, "تم تحديث الكاش: ${knownEmbeddingsCache.size} مستخدم")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في تحديث الكاش: ${e.message}", e)
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
            val unknownEntity = UnknownFaceEntity(
                timestamp = timestamp,
                formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp)),
                imagePath = imageFile.absolutePath,
                vectorEmbedding = embeddingJson
            )

            val db = AppDatabase.getDatabase(context)
            db.unknownFaceDao().insertUnknown(unknownEntity)

            Log.d(TAG, "✅ تم حفظ مجهول: $fileName")

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
        } catch (e: Throwable) {
        }
    }

    fun getLastProcessTime(): Long = lastProcessTime
}
