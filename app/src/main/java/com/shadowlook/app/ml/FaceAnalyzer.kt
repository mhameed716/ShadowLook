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
import java.nio.ByteBuffer
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
        private const val UNKNOWN_CAPTURE_COOLDOWN_MS = 5000L
    }

    private val detector by lazy {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking()
                .build()
            FaceDetection.getClient(options)
        } catch (e: Throwable) {
            Log.e(TAG, "فشل إنشاء ML Kit detector: ${e.message}", e)
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
    private val CACHE_VALIDITY_MS = 10000L

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

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    try {
                        if (faces.isNotEmpty()) {
                            processFaces(faces, mediaImage, imageProxy)
                        } else {
                            // لا يوجد وجوه - امسح الـ overlay وأبلغ
                            try {
                                overlayView.setResults(emptyList())
                                onNoFaceDetected()
                            } catch (e: Throwable) {
                                Log.e(TAG, "خطأ في onNoFaceDetected: ${e.message}")
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
                        Log.e(TAG, "خطأ في إغلاق imageProxy: ${e.message}")
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
                updateKnownEmbeddingsCacheIfNeeded()

                val fullBitmap = try {
                    mediaImage.toBitmapSafe(imageProxy.imageInfo.rotationDegrees)
                } catch (e: Throwable) {
                    Log.e(TAG, "فشل تحويل Image إلى Bitmap: ${e.message}", e)
                    return@launch
                }

                if (fullBitmap == null) {
                    Log.e(TAG, "fullBitmap null")
                    return@launch
                }

                val results = mutableListOf<FaceRecognitionResult>()

                for (face in faces) {
                    try {
                        val boundingBox = face.boundingBox
                        val faceBitmap = cropFaceSafe(fullBitmap, boundingBox)

                        if (faceBitmap != null) {
                            val embedding = try {
                                tfliteHelper.getFaceEmbedding(faceBitmap)
                            } catch (e: Throwable) {
                                Log.e(TAG, "فشل توليد embedding: ${e.message}", e)
                                FloatArray(128) { 0f }
                            }

                            val (matchedId, distance, isMatch) = try {
                                tfliteHelper.findBestMatch(embedding, knownEmbeddingsCache)
                            } catch (e: Throwable) {
                                Triple(null, Float.MAX_VALUE, false)
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
                                    isKnown = true,
                                    faceBitmap = faceBitmap
                                )
                                results.add(result)
                                withContext(Dispatchers.Main) {
                                    try {
                                        onFaceRecognized(result)
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "خطأ في onFaceRecognized: ${e.message}")
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
                                            Log.e(TAG, "خطأ في saveUnknownFace: ${e.message}", e)
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
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في معالجة وجه واحد: ${e.message}", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    try {
                        overlayView.setResults(results)
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في setResults: ${e.message}", e)
                    }
                }

                // تنظيف
                try {
                    if (!fullBitmap.isRecycled) {
                        // لا نعيد تدوير fullBitmap لأنه قد يستخدم مرة أخرى
                    }
                } catch (e: Throwable) {
                }

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
                        Log.e(TAG, "خطأ في تحويل embedding للمستخدم ${user.id}: ${e.message}")
                        null
                    }
                }
                lastCacheUpdateTime = currentTime
                Log.d(TAG, "تم تحديث كاش الـ embeddings: ${knownEmbeddingsCache.size} مستخدم")
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
            Log.e(TAG, "خطأ في جلب المستخدم $userId: ${e.message}")
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

            Log.d(TAG, "✅ تم حفظ وجه مجهول: $fileName")

        } catch (e: Throwable) {
            Log.e(TAG, "❌ فشل حفظ الوجه المجهول: ${e.message}", e)
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
            Log.e(TAG, "خطأ في قص الوجه: ${e.message}")
            null
        }
    }

    // تحويل YUV_420_888 إلى Bitmap بطريقة آمنة وصحيحة
    private fun Image.toBitmapSafe(rotationDegrees: Int): Bitmap? {
        return try {
            // الطريقة الصحيحة لتحويل YUV إلى Bitmap
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
                // Fallback: try simple method
                Log.w(TAG, "فشل YUV conversion، محاولة fallback")
                return toBitmapFallback(rotationDegrees)
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            bitmap
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في toBitmapSafe: ${e.message}", e)
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
            Log.e(TAG, "خطأ في close: ${e.message}", e)
        }
    }
}
