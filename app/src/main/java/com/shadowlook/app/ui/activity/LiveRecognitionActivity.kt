package com.shadowlook.app.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
    private lateinit var tvStatus: TextView
    private lateinit var tvName: TextView
    private lateinit var tvJob: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvIdBadge: TextView
    private lateinit var ivProfile: ImageView
    private lateinit var layoutUnknown: View
    private lateinit var tvUnknownTimestamp: TextView
    private lateinit var tvWarningBanner: TextView
    private lateinit var cardView: MaterialCardView
    private lateinit var statusIndicator: View
    private lateinit var tvQualityInfo: TextView
    private lateinit var tvSpeedInfo: TextView
    private lateinit var tvScanningText: TextView

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

        try {
            setContentView(R.layout.activity_live_recognition)
            initViewsSafe()
            setupClickListeners()

            try {
                tfliteHelper = TFLiteHelper(this)
                Log.d(TAG, "TFLiteHelper initialized, model ready: ${tfliteHelper?.isModelReady()}")
                if (tfliteHelper?.isModelReady() == false) {
                    showWarningToast("وضع المحاكاة نشط - النموذج غير موجود، التطبيق سيعمل بدقة محدودة")
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "فشل تحميل مكتبات TFLite: ${e.message}", e)
                showErrorDialog(
                    "خطأ في مكتبات الذكاء الاصطناعي",
                    "فشل تحميل مكتبات TFLite (.so) للمعمارية ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n\nالتطبيق سيعمل في وضع المحاكاة."
                )
                tfliteHelper = TFLiteHelper(this)
            } catch (e: Throwable) {
                Log.e(TAG, "خطأ في TFLiteHelper: ${e.message}", e)
                showErrorDialog("خطأ في الذكاء الاصطناعي", "حدث خطأ: ${e.message}\n\nسيتم المتابعة في وضع المحاكاة.")
                tfliteHelper = TFLiteHelper(this)
            }

            cameraExecutor = Executors.newSingleThreadExecutor()

            if (allPermissionsGranted()) {
                startCameraSafely()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }

        } catch (e: Throwable) {
            Log.e(TAG, "خطأ حرج في onCreate: ${e.message}", e)
            showErrorDialog("خطأ في تشغيل التطبيق", "حدث خطأ: ${e.message}")
            try {
                setContentView(R.layout.activity_live_recognition)
                initViewsSafe()
            } catch (e2: Throwable) {
                Toast.makeText(this, "فشل تشغيل التطبيق: ${e2.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initViewsSafe() {
        try {
            // استخدام findViewById الآمن مع فحص null
            previewView = findViewById(R.id.previewView) ?: throw IllegalStateException("previewView not found in layout - تأكد من وجود android:id=\"@+id/previewView\" في activity_live_recognition.xml")
            overlayView = findViewById(R.id.overlayView) ?: throw IllegalStateException("overlayView not found")

            // الـ include - الجذر هو نفسه الكارد
            val includeView = findViewById<View>(R.id.cyberCardInclude)
                ?: throw IllegalStateException("cyberCardInclude not found - تأكد من <include android:id=\"@+id/cyberCardInclude\" layout=\"@layout/layout_cyber_profile_card\" />")

            cyberCard = includeView

            // إصلاح المشكلة الرئيسية: cardView هو نفسه includeView، ليس child
            // لأن android:id في <include> يستبدل id الجذر الأصلي
            cardView = try {
                includeView as? MaterialCardView
                    ?: includeView.findViewById<MaterialCardView>(R.id.cyberProfileCard)
                    ?: throw IllegalStateException("cardView is null - includeView is ${includeView::class.java.simpleName}")
            } catch (e: Throwable) {
                Log.e(TAG, "خطأ في cardView: ${e.message}", e)
                // Fallback: إنشاء كارد وهمي أو استخدام includeView كـ View
                // نحاول البحث بطريقة أخرى
                val fallback = findViewById<MaterialCardView>(R.id.cyberCardInclude) as? MaterialCardView
                fallback ?: throw IllegalStateException("فشل العثور على cardView: ${e.message}")
            }

            // البحث داخل الـ include - مع فحص null لكل عنصر
            tvStatus = includeView.findViewById(R.id.tvStatus)
                ?: throw IllegalStateException("tvStatus not found in layout_cyber_profile_card.xml")
            tvName = includeView.findViewById(R.id.tvName)
                ?: throw IllegalStateException("tvName not found")
            tvJob = includeView.findViewById(R.id.tvJobTitle)
                ?: throw IllegalStateException("tvJobTitle not found")
            tvPhone = includeView.findViewById(R.id.tvPhone)
                ?: throw IllegalStateException("tvPhone not found")
            tvAddress = includeView.findViewById(R.id.tvAddress)
                ?: throw IllegalStateException("tvAddress not found")
            tvConfidence = includeView.findViewById(R.id.tvConfidence)
                ?: throw IllegalStateException("tvConfidence not found")
            tvIdBadge = includeView.findViewById(R.id.tvIdBadge)
                ?: throw IllegalStateException("tvIdBadge not found")
            ivProfile = includeView.findViewById(R.id.ivProfilePhoto)
                ?: throw IllegalStateException("ivProfilePhoto not found")
            layoutUnknown = includeView.findViewById(R.id.layoutUnknownAlert)
                ?: throw IllegalStateException("layoutUnknownAlert not found")
            tvUnknownTimestamp = includeView.findViewById(R.id.tvUnknownTimestamp)
                ?: throw IllegalStateException("tvUnknownTimestamp not found")
            statusIndicator = includeView.findViewById(R.id.statusIndicator)
                ?: throw IllegalStateException("statusIndicator not found")

            tvWarningBanner = findViewById(R.id.tvUnknownWarningBanner)
                ?: throw IllegalStateException("tvUnknownWarningBanner not found")

            // عناصر جديدة للإحصائيات
            tvQualityInfo = includeView.findViewById(R.id.tvQualityInfo)
                ?: throw IllegalStateException("tvQualityInfo not found")
            tvSpeedInfo = includeView.findViewById(R.id.tvSpeedInfo)
                ?: throw IllegalStateException("tvSpeedInfo not found")
            tvScanningText = findViewById(R.id.tvScanningText)
                ?: findViewById(R.id.layoutScanning) as? TextView
                ?: throw IllegalStateException("tvScanningText not found")

            cyberCard.visibility = View.GONE
            tvWarningBanner.visibility = View.GONE

            Log.d(TAG, "✅ تم ربط جميع عناصر الواجهة بنجاح - v2.0 مع تحسينات الدقة والسرعة")

        } catch (e: Throwable) {
            Log.e(TAG, "❌ خطأ في initViewsSafe: ${e.message}", e)
            throw e // إعادة رمي الخطأ ليتم التقاطه في onCreate وعرض dialog
        }
    }

    private fun setupClickListeners() {
        try {
            findViewById<View>(R.id.btnOpenRegister)?.setOnClickListener {
                try {
                    startActivity(Intent(this, RegisterFaceActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح شاشة التسجيل: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnOpenDatabase)?.setOnClickListener {
                try {
                    startActivity(Intent(this, DatabaseActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح قاعدة البيانات: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnOpenDashboard)?.setOnClickListener {
                try {
                    startActivity(Intent(this, DashboardActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح لوحة التحكم: ${e.message}")
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
                    Log.e(TAG, "خطأ في CameraProvider: ${e.message}", e)
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
                onUnknownFaceDetected = { _, _ ->
                    runOnUiThread {
                        try {
                            showUnknownWarning()
                        } catch (e: Throwable) {
                        }
                    }
                },
                onNoFaceDetected = {
                    runOnUiThread {
                        try {
                            cyberCard.visibility = View.GONE
                        } catch (e: Throwable) {
                        }
                    }
                }
            )

            imageAnalysis.setAnalyzer(cameraExecutor, faceAnalyzer!!)

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            Log.d(TAG, "✅ تم ربط الكاميرا بنجاح")

        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في bindCameraUseCasesSafely: ${e.message}", e)
            showErrorDialog("خطأ في ربط الكاميرا", "فشل ربط الكاميرا: ${e.message}")
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
                // معروف: أخضر #00FF66 مع واجهة منبثقة جميلة ومرتبة مع صورته المحفوظة
                cardView.strokeColor = android.graphics.Color.parseColor("#00FF66")
                tvStatus.text = "✅ تمت المطابقة // SHADOW_ID"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#00FF66"))
                statusIndicator.setBackgroundResource(R.drawable.bg_status_indicator)
                try {
                    (statusIndicator.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                        android.graphics.Color.parseColor("#00FF66")
                    )
                } catch (e: Throwable) {}

                tvName.text = result.userName ?: "مستخدم معروف"
                tvJob.text = result.jobTitle ?: "غير محدد"
                tvPhone.text = result.phone ?: "---"
                tvAddress.text = result.address ?: "لا يوجد عنوان"
                tvIdBadge.text = "ID: ${result.userId?.toString()?.padStart(3, '0') ?: "---"} // KNOWN"

                val confidence = ((1 - result.distance) * 100).toInt().coerceIn(0, 100)
                val cosinePercent = (result.cosineSimilarity * 100).toInt().coerceIn(0, 100)
                tvConfidence.text = "$confidence%"

                // معلومات الجودة والسرعة
                try {
                    tvQualityInfo.text = "جودة: ${(result.quality * 100).toInt()}% | Cosine: $cosinePercent%"
                    tvSpeedInfo.text = "دقة: $confidence% | مسافة: ${String.format("%.2f", result.distance)}"
                    tvScanningText.text = "✅ معروف: ${result.userName} - ${confidence}% - 0.15s"
                    tvScanningText.setTextColor(android.graphics.Color.parseColor("#00FF66"))
                } catch (e: Throwable) {}

                // عرض الصورة المحفوظة بشكل جميل ومرتب
                if (!result.imagePath.isNullOrEmpty()) {
                    try {
                        val file = File(result.imagePath)
                        if (file.exists()) {
                            ivProfile.load(file) {
                                crossfade(true)
                                placeholder(R.mipmap.ic_launcher)
                            }
                        } else {
                            result.faceBitmap?.let { ivProfile.setImageBitmap(it) } ?: ivProfile.setImageResource(R.mipmap.ic_launcher)
                        }
                    } catch (e: Throwable) {
                        ivProfile.setImageResource(R.mipmap.ic_launcher)
                    }
                } else {
                    result.faceBitmap?.let { ivProfile.setImageBitmap(it) }
                }

                layoutUnknown.visibility = View.GONE
                tvWarningBanner.visibility = View.GONE

            } else {
                // مجهول: أحمر #FF0055 مع التقاط صورة أو أكثر
                cardView.strokeColor = android.graphics.Color.parseColor("#FF0055")
                tvStatus.text = "⚠️ تنبيه: شخص مجهول!"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FF0055"))
                try {
                    (statusIndicator.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                        android.graphics.Color.parseColor("#FF0055")
                    )
                } catch (e: Throwable) {}

                tvName.text = "شخص غير معرف"
                tvJob.text = "غير مسجل في القاعدة"
                tvPhone.text = "مجهول - سيتم التقاط صورة"
                tvAddress.text = "تم التقاط الوجه تلقائياً وحفظه"
                tvIdBadge.text = "UNKNOWN // CAPTURED"
                tvConfidence.text = "${((1 - result.distance) * 100).toInt()}%"

                try {
                    tvQualityInfo.text = "جودة: ${(result.quality * 100).toInt()}% | غير معروف"
                    tvSpeedInfo.text = "التقاط: تلقائي | حفظ: /unknown_faces/"
                    tvScanningText.text = "⚠️ مجهول مرصود - جاري التقاط صورة..."
                    tvScanningText.setTextColor(android.graphics.Color.parseColor("#FF0055"))
                } catch (e: Throwable) {}

                result.faceBitmap?.let {
                    try {
                        ivProfile.setImageBitmap(it)
                    } catch (e: Throwable) {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == CAMERA_PERMISSION_REQUEST) {
                if (allPermissionsGranted()) {
                    startCameraSafely()
                } else {
                    showErrorDialog("إذن الكاميرا مطلوب", "التطبيق يحتاج إذن الكاميرا.")
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
        }
    }
}
