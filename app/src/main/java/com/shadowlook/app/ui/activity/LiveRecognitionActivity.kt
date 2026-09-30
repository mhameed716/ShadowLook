package com.shadowlook.app.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.card.MaterialCardView
import com.shadowlook.app.R
import com.shadowlook.app.ml.FaceAnalyzer
import com.shadowlook.app.ml.TFLiteHelper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveRecognitionActivity : AppCompatActivity() {

    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var overlayView: com.shadowlook.app.ui.view.OverlayView
    private lateinit var cyberCard: View
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvName: android.widget.TextView
    private lateinit var tvJob: android.widget.TextView
    private lateinit var tvPhone: android.widget.TextView
    private lateinit var tvAddress: android.widget.TextView
    private lateinit var tvConfidence: android.widget.TextView
    private lateinit var tvIdBadge: android.widget.TextView
    private lateinit var ivProfile: android.widget.ImageView
    private lateinit var layoutUnknown: View
    private lateinit var tvUnknownTimestamp: android.widget.TextView
    private lateinit var tvWarningBanner: android.widget.TextView
    private lateinit var cardView: MaterialCardView
    private lateinit var statusIndicator: View

    private lateinit var cameraExecutor: ExecutorService
    private var tfliteHelper: TFLiteHelper? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val TAG = "LiveRecognition"
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // معالجة شاملة للأخطاء لمنع انهيار التطبيق
        try {
            setContentView(R.layout.activity_live_recognition)
            initViews()
            setupClickListeners()

            // تهيئة TFLiteHelper مع معالجة أخطاء .so files
            try {
                tfliteHelper = TFLiteHelper(this)
                Log.d(TAG, "TFLiteHelper initialized, model ready: ${tfliteHelper?.isModelReady()}")
                if (tfliteHelper?.isModelReady() == false) {
                    showWarningToast("وضع المحاكاة نشط - النموذج غير موجود، التطبيق سيعمل بدقة محدودة")
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "فشل تحميل مكتبات TFLite الأصلية: ${e.message}", e)
                showErrorDialog(
                    "خطأ في مكتبات الذكاء الاصطناعي",
                    "فشل تحميل مكتبات TFLite (.so files) للمعمارية ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n\nالتطبيق سيعمل في وضع المحاكاة.\n\nالتفاصيل: ${e.message}"
                )
                // إنشاء helper وهمي
                tfliteHelper = TFLiteHelper(this)
            } catch (e: Throwable) {
                Log.e(TAG, "خطأ في تهيئة TFLiteHelper: ${e.message}", e)
                showErrorDialog(
                    "خطأ في تهيئة الذكاء الاصطناعي",
                    "حدث خطأ أثناء تهيئة نموذج التعرف: ${e.message}\n\nسيتم المتابعة في وضع المحاكاة."
                )
                tfliteHelper = TFLiteHelper(this)
            }

            cameraExecutor = Executors.newSingleThreadExecutor()

            if (allPermissionsGranted()) {
                startCameraSafely()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST
                )
            }

        } catch (e: Throwable) {
            Log.e(TAG, "خطأ حرج في onCreate: ${e.message}", e)
            showErrorDialog(
                "خطأ في تشغيل التطبيق",
                "حدث خطأ غير متوقع عند بدء التطبيق:\n${e.message}\n\nسيتم محاولة المتابعة."
            )
            // محاولة تهيئة أساسية
            try {
                setContentView(R.layout.activity_live_recognition)
                initViews()
            } catch (e2: Throwable) {
                Log.e(TAG, "فشل حتى في التهيئة الأساسية: ${e2.message}", e2)
                Toast.makeText(this, "فشل تشغيل التطبيق: ${e2.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initViews() {
        try {
            previewView = findViewById(R.id.previewView)
            overlayView = findViewById(R.id.overlayView)
            val include = findViewById<View>(R.id.cyberCardInclude)
            cyberCard = include
            tvStatus = include.findViewById(R.id.tvStatus)
            tvName = include.findViewById(R.id.tvName)
            tvJob = include.findViewById(R.id.tvJobTitle)
            tvPhone = include.findViewById(R.id.tvPhone)
            tvAddress = include.findViewById(R.id.tvAddress)
            tvConfidence = include.findViewById(R.id.tvConfidence)
            tvIdBadge = include.findViewById(R.id.tvIdBadge)
            ivProfile = include.findViewById(R.id.ivProfilePhoto)
            layoutUnknown = include.findViewById(R.id.layoutUnknownAlert)
            tvUnknownTimestamp = include.findViewById(R.id.tvUnknownTimestamp)
            tvWarningBanner = findViewById(R.id.tvUnknownWarningBanner)
            cardView = include.findViewById(R.id.cyberProfileCard)
            statusIndicator = include.findViewById(R.id.statusIndicator)

            cyberCard.visibility = View.GONE
            tvWarningBanner.visibility = View.GONE
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في initViews: ${e.message}", e)
            throw e
        }
    }

    private fun setupClickListeners() {
        try {
            findViewById<View>(R.id.btnOpenRegister).setOnClickListener {
                try {
                    startActivity(Intent(this, RegisterFaceActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح شاشة التسجيل: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnOpenDatabase).setOnClickListener {
                try {
                    startActivity(Intent(this, DatabaseActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح قاعدة البيانات: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في setupClickListeners: ${e.message}", e)
        }
    }

    private fun startCameraSafely() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCasesSafely()
                } catch (e: Throwable) {
                    Log.e(TAG, "خطأ في الحصول على CameraProvider: ${e.message}", e)
                    showErrorDialog("خطأ في الكاميرا", "فشل تهيئة الكاميرا: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في startCameraSafely: ${e.message}", e)
            showErrorDialog("خطأ في الكاميرا", "فشل بدء الكاميرا: ${e.message}")
        }
    }

    private fun bindCameraUseCasesSafely() {
        try {
            val cameraProvider = cameraProvider ?: return
            val helper = tfliteHelper ?: TFLiteHelper(this).also { tfliteHelper = it }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            faceAnalyzer = FaceAnalyzer(
                context = this,
                overlayView = overlayView,
                tfliteHelper = helper,
                onFaceRecognized = { result ->
                    runOnUiThread {
                        try {
                            handleFaceResult(result)
                        } catch (e: Throwable) {
                            Log.e(TAG, "خطأ في handleFaceResult: ${e.message}", e)
                        }
                    }
                },
                onUnknownFaceDetected = { bitmap, embedding ->
                    runOnUiThread {
                        try {
                            showUnknownWarning()
                        } catch (e: Throwable) {
                            Log.e(TAG, "خطأ في showUnknownWarning: ${e.message}", e)
                        }
                    }
                }
            )

            imageAnalysis.setAnalyzer(cameraExecutor, faceAnalyzer!!)

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.d(TAG, "✅ تم ربط الكاميرا بنجاح")

        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في bindCameraUseCasesSafely: ${e.message}", e)
            showErrorDialog("خطأ في ربط الكاميرا", "فشل ربط حالات استخدام الكاميرا: ${e.message}\n\nتأكد من منح إذن الكاميرا.")
        }
    }

    private fun handleFaceResult(result: FaceAnalyzer.FaceRecognitionResult) {
        try {
            if (result.boundingBox.isEmpty) {
                cyberCard.visibility = View.GONE
                return
            }

            cyberCard.visibility = View.VISIBLE

            if (result.isKnown) {
                cardView.strokeColor = android.graphics.Color.parseColor("#00F0FF")
                tvStatus.text = "تمت المطابقة // SHADOW_ID"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#00F0FF"))

                tvName.text = result.userName ?: "مستخدم معروف"
                tvJob.text = result.jobTitle ?: "غير محدد"
                tvPhone.text = result.phone ?: "---"
                tvAddress.text = result.address ?: "لا يوجد عنوان"
                tvIdBadge.text = "ID: ${result.userId?.toString()?.padStart(3, '0') ?: "---"}"

                val confidence = ((1 - result.distance) * 100).toInt().coerceIn(0, 100)
                tvConfidence.text = "$confidence%"

                if (!result.imagePath.isNullOrEmpty()) {
                    try {
                        val file = File(result.imagePath)
                        if (file.exists()) {
                            ivProfile.load(file)
                        } else {
                            ivProfile.setImageResource(R.mipmap.ic_launcher)
                        }
                    } catch (e: Throwable) {
                        ivProfile.setImageResource(R.mipmap.ic_launcher)
                    }
                }

                layoutUnknown.visibility = View.GONE
                tvWarningBanner.visibility = View.GONE

            } else {
                cardView.strokeColor = android.graphics.Color.parseColor("#FF0055")
                tvStatus.text = "تنبيه: تم رصد شخص مجهول!"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FF0055"))

                tvName.text = "شخص غير معرف"
                tvJob.text = "غير مسجل في القاعدة"
                tvPhone.text = "مجهول"
                tvAddress.text = "تم التقاط الوجه في الخلفية"
                tvIdBadge.text = "UNKNOWN"
                tvConfidence.text = "${((1 - result.distance) * 100).toInt()}%"

                result.faceBitmap?.let {
                    try {
                        ivProfile.setImageBitmap(it)
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في عرض صورة الوجه: ${e.message}")
                    }
                }

                layoutUnknown.visibility = View.VISIBLE
                tvUnknownTimestamp.text = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في handleFaceResult: ${e.message}", e)
        }
    }

    private fun showUnknownWarning() {
        try {
            tvWarningBanner.visibility = View.VISIBLE
            tvWarningBanner.text = "⚠️ تنبيه: تم رصد شخص مجهول! تم تسجيل الوجه تلقائياً"
            tvWarningBanner.postDelayed({
                try {
                    tvWarningBanner.visibility = View.GONE
                } catch (e: Throwable) {
                }
            }, 3000)
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في showUnknownWarning: ${e.message}", e)
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        try {
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("حسناً") { dialog, _ -> dialog.dismiss() }
                    .setNeutralButton("إعادة المحاولة") { _, _ ->
                        try {
                            startCameraSafely()
                        } catch (e: Throwable) {
                        }
                    }
                    .show()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "فشل حتى في عرض رسالة الخطأ: ${e.message}", e)
            Toast.makeText(this, "$title: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWarningToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == CAMERA_PERMISSION_REQUEST) {
                if (allPermissionsGranted()) {
                    startCameraSafely()
                } else {
                    showErrorDialog(
                        "إذن الكاميرا مطلوب",
                        "التطبيق يحتاج إذن الكاميرا لعمل التعرف على الوجوه. الرجاء منح الإذن من إعدادات التطبيق."
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في onRequestPermissionsResult: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            cameraExecutor.shutdown()
            faceAnalyzer?.close()
            tfliteHelper?.close()
            cameraProvider?.unbindAll()
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في onDestroy: ${e.message}", e)
        }
    }
}
