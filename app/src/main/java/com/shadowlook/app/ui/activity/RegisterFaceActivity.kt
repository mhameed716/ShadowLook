package com.shadowlook.app.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Toast
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
    private lateinit var tfliteHelper: TFLiteHelper
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var latestFaceBitmap: Bitmap? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ML Kit detector for registration preview
    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register_face)

        previewView = findViewById(R.id.previewViewRegister)
        overlayView = findViewById(R.id.overlayViewRegister)
        etName = findViewById(R.id.etFullName)
        etPhone = findViewById(R.id.etPhone)
        etJob = findViewById(R.id.etJobTitle)
        etAddress = findViewById(R.id.etAddress)

        tfliteHelper = TFLiteHelper(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1002)
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCaptureEnroll).setOnClickListener {
            captureAndEnroll()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
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
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            // Convert to analyzer results for overlay
                            val results = faces.map { face ->
                                com.shadowlook.app.ml.FaceAnalyzer.FaceRecognitionResult(
                                    face = face,
                                    boundingBox = face.boundingBox,
                                    userId = null,
                                    userName = "وجه مكتشف",
                                    jobTitle = null,
                                    phone = null,
                                    address = null,
                                    imagePath = null,
                                    distance = 0f,
                                    isKnown = true,
                                    faceBitmap = null
                                )
                            }
                            runOnUiThread {
                                overlayView.setResults(results)
                            }
                        } else {
                            runOnUiThread { overlayView.setResults(emptyList()) }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تشغيل الكاميرا: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureAndEnroll() {
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
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    if (bitmap == null) {
                        Toast.makeText(this@RegisterFaceActivity, "فشل التقاط الصورة", Toast.LENGTH_SHORT).show()
                        return
                    }

                    // كشف الوجه في الصورة الملتقطة
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    detector.process(inputImage)
                        .addOnSuccessListener { faces ->
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

                            // حفظ في قاعدة البيانات
                            scope.launch {
                                saveUser(cropped, name, phone, job, address)
                            }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this@RegisterFaceActivity, "خطأ في كشف الوجه: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@RegisterFaceActivity, "خطأ في الالتقاط: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private suspend fun saveUser(faceBitmap: Bitmap, name: String, phone: String, job: String, address: String) = withContext(Dispatchers.IO) {
        try {
            // 1. توليد embedding
            val embedding = tfliteHelper.getFaceEmbedding(faceBitmap)
            val embeddingJson = Converters.embeddingToJson(embedding)

            // 2. حفظ الصورة
            val knownDir = File(this@RegisterFaceActivity.filesDir, "known_faces")
            if (!knownDir.exists()) knownDir.mkdirs()

            val fileName = "known_${System.currentTimeMillis()}.jpg"
            val imageFile = File(knownDir, fileName)
            FileOutputStream(imageFile).use { out ->
                faceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // 3. حفظ في Room
            val entity = UserFaceEntity(
                name = name,
                phone = phone,
                jobTitle = job,
                address = address,
                imagePath = imageFile.absolutePath,
                vectorEmbedding = embeddingJson
            )

            val db = AppDatabase.getDatabase(this@RegisterFaceActivity)
            db.userFaceDao().insertKnown(entity)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RegisterFaceActivity,
                    "✅ تم تسجيل $name بنجاح! // ENROLLED",
                    Toast.LENGTH_LONG
                ).show()
                // مسح الحقول
                etName.text?.clear()
                etPhone.text?.clear()
                etJob.text?.clear()
                etAddress.text?.clear()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RegisterFaceActivity, "❌ فشل الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            // تدوير حسب rotation
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
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
        } catch (e: Exception) { null }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scope.cancel()
        detector.close()
        tfliteHelper.close()
    }
}
