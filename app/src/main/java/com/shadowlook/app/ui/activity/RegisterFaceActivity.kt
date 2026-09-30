package com.shadowlook.app.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shadowlook.app.R
import com.shadowlook.app.data.local.converters.Converters
import com.shadowlook.app.data.local.db.AppDatabase
import com.shadowlook.app.data.local.entity.UserFaceEntity
import com.shadowlook.app.ml.TFLiteHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RegisterFaceActivity : AppCompatActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlayView: com.shadowlook.app.ui.view.OverlayView
    private lateinit var etName: com.google.android.material.textfield.TextInputEditText
    private lateinit var etPhone: com.google.android.material.textfield.TextInputEditText
    private lateinit var etJob: com.google.android.material.textfield.TextInputEditText
    private lateinit var etAddress: com.google.android.material.textfield.TextInputEditText

    private lateinit var cameraExecutor: ExecutorService
    private var tfliteHelper: TFLiteHelper? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val detector by lazy {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            FaceDetection.getClient(options)
        } catch (e: Throwable) {
            Log.e("RegisterFace", "فشل إنشاء ML Kit detector: ${e.message}", e)
            // Fallback detector
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
            FaceDetection.getClient(options)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_register_face)

            previewView = findViewById(R.id.previewViewRegister)
            overlayView = findViewById(R.id.overlayViewRegister)
            etName = findViewById(R.id.etFullName)
            etPhone = findViewById(R.id.etPhone)
            etJob = findViewById(R.id.etJobTitle)
            etAddress = findViewById(R.id.etAddress)

            try {
                tfliteHelper = TFLiteHelper(this)
            } catch (e: Throwable) {
                Log.e("RegisterFace", "خطأ في TFLiteHelper: ${e.message}", e)
                tfliteHelper = TFLiteHelper(this)
            }

            cameraExecutor = Executors.newSingleThreadExecutor()

            if (allPermissionsGranted()) {
                startCameraSafely()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
            }

            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCaptureEnroll).setOnClickListener {
                try {
                    captureAndEnroll()
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل التقاط الوجه: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.e("RegisterFace", "خطأ في onCreate: ${e.message}", e)
            Toast.makeText(this, "خطأ في تشغيل شاشة التسجيل: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCameraSafely() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindUseCasesSafely()
                } catch (e: Throwable) {
                    Log.e("RegisterFace", "خطأ في CameraProvider: ${e.message}", e)
                    showErrorDialog("خطأ في الكاميرا", "فشل تهيئة الكاميرا: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Throwable) {
            Log.e("RegisterFace", "خطأ في startCamera: ${e.message}", e)
            showErrorDialog("خطأ في الكاميرا", "فشل بدء الكاميرا: ${e.message}")
        }
    }

    private fun bindUseCasesSafely() {
        try {
            val cameraProvider = cameraProvider ?: return

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        detector.process(inputImage)
                            .addOnSuccessListener { faces ->
                                try {
                                    if (faces.isNotEmpty()) {
                                        val results = faces.map { face ->
                                            com.shadowlook.app.ml.FaceAnalyzer.FaceRecognitionResult(
                                                face = face,
                                                boundingBox = face.boundingBox,
                                                trackingId = try { face.trackingId } catch (e: Throwable) { null },
                                                userId = null,
                                                userName = "وجه مكتشف",
                                                jobTitle = null,
                                                phone = null,
                                                address = null,
                                                imagePath = null,
                                                distance = 0f,
                                                cosineSimilarity = 0f,
                                                similarityPercent = 95f,
                                                quality = 0.9f,
                                                isKnown = true,
                                                faceBitmap = null,
                                                headEulerX = 0f,
                                                headEulerY = 0f,
                                                headEulerZ = 0f
                                            )
                                        }
                                        runOnUiThread {
                                            try {
                                                overlayView.setResults(results)
                                            } catch (e: Throwable) {
                                            }
                                        }
                                    } else {
                                        runOnUiThread { overlayView.setResults(emptyList()) }
                                    }
                                } catch (e: Throwable) {
                                    Log.e("RegisterFace", "خطأ في معالجة الوجوه: ${e.message}")
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                } catch (e: Throwable) {
                    try {
                        imageProxy.close()
                    } catch (e2: Throwable) {
                    }
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Throwable) {
            Log.e("RegisterFace", "خطأ في bindUseCases: ${e.message}", e)
            showErrorDialog("خطأ في الكاميرا", "فشل ربط الكاميرا: ${e.message}")
        }
    }

    private fun captureAndEnroll() {
        try {
            val name = etName.text?.toString()?.trim() ?: ""
            val phone = etPhone.text?.toString()?.trim() ?: ""
            val job = etJob.text?.toString()?.trim() ?: ""
            val address = etAddress.text?.toString()?.trim() ?: ""

            if (name.isEmpty()) {
                etName.error = "الاسم مطلوب"
                Toast.makeText(this, "الرجاء إدخال الاسم الكامل", Toast.LENGTH_SHORT).show()
                return
            }

            val imageCapture = imageCapture ?: run {
                Toast.makeText(this, "الكاميرا غير جاهزة", Toast.LENGTH_SHORT).show()
                return
            }

            Toast.makeText(this, "جاري التقاط الوجه...", Toast.LENGTH_SHORT).show()

            imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()

                            if (bitmap == null) {
                                Toast.makeText(this@RegisterFaceActivity, "فشل التقاط الصورة", Toast.LENGTH_SHORT).show()
                                return
                            }

                            val inputImage = InputImage.fromBitmap(bitmap, 0)
                            detector.process(inputImage)
                                .addOnSuccessListener { faces ->
                                    try {
                                        if (faces.isEmpty()) {
                                            Toast.makeText(this@RegisterFaceActivity, "لم يتم كشف أي وجه! اقترب أكثر", Toast.LENGTH_LONG).show()
                                            return@addOnSuccessListener
                                        }

                                        val face = faces[0]
                                        val cropped = cropFace(bitmap, face.boundingBox)
                                        if (cropped == null) {
                                            Toast.makeText(this@RegisterFaceActivity, "فشل قص الوجه", Toast.LENGTH_SHORT).show()
                                            return@addOnSuccessListener
                                        }

                                        scope.launch {
                                            saveUser(cropped, name, phone, job, address)
                                        }
                                    } catch (e: Throwable) {
                                        Toast.makeText(this@RegisterFaceActivity, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this@RegisterFaceActivity, "خطأ في كشف الوجه: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        } catch (e: Throwable) {
                            try {
                                image.close()
                            } catch (e2: Throwable) {
                            }
                            Toast.makeText(this@RegisterFaceActivity, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(this@RegisterFaceActivity, "خطأ في الالتقاط: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e("RegisterFace", "خطأ في captureAndEnroll: ${e.message}", e)
            showErrorDialog("خطأ", "فشل التسجيل: ${e.message}")
        }
    }

    private suspend fun saveUser(faceBitmap: Bitmap, name: String, phone: String, job: String, address: String) = withContext(Dispatchers.IO) {
        try {
            // === Pipeline الكامل المطلوب ===
            // 1. إضافة الملف: mobilefacenet.tflite في assets/ - يتم في TFLiteHelper
            // 2. الكشف الأولي: تم في captureAndEnroll - ML Kit كشف الوجه وقص المنطقة فقط
            // 3. التجهيز: 112x112 + Normalization - يتم في TFLiteHelper.getFaceEmbedding
            // 4. التمرير: Inference للحصول على Embedding - يتم في TFLiteHelper
            // 5. المقارنة أو الحفظ: حفظ البصمة في قاعدة البيانات - هنا

            val pipeline = com.shadowlook.app.ml.FaceRecognitionPipeline(this@RegisterFaceActivity)
            
            // التحقق من وجود النموذج (الخطوة 1)
            val modelExists = pipeline.checkModelExists()
            Log.d("RegisterFace", "الخطوة 1: فحص النموذج - موجود: $modelExists")

            // الخطوة 3+4: Pre-processing + Inference -> Embedding
            val embedding = pipeline.getEmbedding(faceBitmap)
            Log.d("RegisterFace", "الخطوة 3+4: تم الحصول على Embedding - الحجم: ${embedding.size}")

            // الخطوة 5: الحفظ - حفظ البصمة في قاعدة البيانات عند التسجيل
            val id = pipeline.saveNewFace(name, phone, job, address, faceBitmap, embedding)
            
            withContext(Dispatchers.Main) {
                if (id > 0) {
                    Toast.makeText(this@RegisterFaceActivity, "✅ تم تسجيل $name بنجاح - ID: $id - ${if (modelExists) "نموذج حقيقي" else "وضع محاكاة"}", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@RegisterFaceActivity, "فشل الحفظ", Toast.LENGTH_SHORT).show()
                }
            }

            pipeline.close()

        } catch (e: Throwable) {
            Log.e("RegisterFace", "خطأ في saveUser: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RegisterFaceActivity, "فشل التسجيل: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Throwable) {
            null
        }
    }

    private fun cropFace(fullBitmap: Bitmap, boundingBox: android.graphics.Rect): Bitmap? {
        return try {
            val padding = 30
            val left = (boundingBox.left - padding).coerceAtLeast(0)
            val top = (boundingBox.top - padding).coerceAtLeast(0)
            val right = (boundingBox.right + padding).coerceAtMost(fullBitmap.width)
            val bottom = (boundingBox.bottom + padding).coerceAtMost(fullBitmap.height)
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0) Bitmap.createBitmap(fullBitmap, left, top, w, h) else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun showErrorDialog(title: String, message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("حسناً") { d, _ -> d.dismiss() }
                .show()
        } catch (e: Throwable) {
            Toast.makeText(this, "$title: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            cameraExecutor.shutdown()
            scope.cancel()
            detector.close()
            tfliteHelper?.close()
        } catch (e: Throwable) {
        }
    }
}
