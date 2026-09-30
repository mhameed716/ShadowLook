package com.shadowlook.app.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.shadowlook.app.data.local.db.AppDatabase
import com.shadowlook.app.databinding.ActivityLiveRecognitionBinding
import com.shadowlook.app.ml.FaceAnalyzer
import com.shadowlook.app.ml.TFLiteHelper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveRecognitionActivity : AppCompatActivity() {

    private lateinit var binding: com.shadowlook.app.databinding.ActivityLiveRecognitionBinding
    // Using view binding manually to avoid generated binding issues - we'll use findViewById fallback
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
    private lateinit var tfliteHelper: TFLiteHelper
    private var faceAnalyzer: FaceAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_recognition)

        initViews()
        tfliteHelper = TFLiteHelper(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // طلب إذن الكاميرا
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }

        setupClickListeners()
    }

    private fun initViews() {
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

        // إخفاء الكارد في البداية
        cyberCard.visibility = View.GONE
        tvWarningBanner.visibility = View.GONE
    }

    private fun setupClickListeners() {
        findViewById<View>(R.id.btnOpenRegister).setOnClickListener {
            startActivity(Intent(this, RegisterFaceActivity::class.java))
        }
        findViewById<View>(R.id.btnOpenDatabase).setOnClickListener {
            startActivity(Intent(this, DatabaseActivity::class.java))
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

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
            tfliteHelper = tfliteHelper,
            onFaceRecognized = { result ->
                runOnUiThread {
                    handleFaceResult(result)
                }
            },
            onUnknownFaceDetected = { bitmap, embedding ->
                runOnUiThread {
                    showUnknownWarning()
                }
            }
        )

        imageAnalysis.setAnalyzer(cameraExecutor, faceAnalyzer!!)

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تشغيل الكاميرا: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFaceResult(result: FaceAnalyzer.FaceRecognitionResult) {
        if (result.boundingBox.isEmpty) {
            cyberCard.visibility = View.GONE
            return
        }

        cyberCard.visibility = View.VISIBLE

        if (result.isKnown) {
            // وجه معروف - Neon Cyan
            cardView.strokeColor = android.graphics.Color.parseColor("#00F0FF")
            tvStatus.text = "تمت المطابقة // SHADOW_ID"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#00F0FF"))
            statusIndicator.setBackgroundResource(R.drawable.bg_status_indicator)

            tvName.text = result.userName ?: "مستخدم معروف"
            tvJob.text = result.jobTitle ?: "غير محدد"
            tvPhone.text = result.phone ?: "---"
            tvAddress.text = result.address ?: "لا يوجد عنوان"
            tvIdBadge.text = "ID: ${result.userId?.toString()?.padStart(3, '0') ?: "---"}"

            val confidence = ((1 - result.distance) * 100).toInt().coerceIn(0, 100)
            tvConfidence.text = "$confidence%"

            // تحميل صورة الملف الشخصي
            if (!result.imagePath.isNullOrEmpty()) {
                val file = File(result.imagePath)
                if (file.exists()) {
                    ivProfile.load(file)
                } else {
                    ivProfile.setImageResource(R.mipmap.ic_launcher)
                }
            }

            layoutUnknown.visibility = View.GONE
            tvWarningBanner.visibility = View.GONE

        } else {
            // وجه مجهول - Neon Red
            cardView.strokeColor = android.graphics.Color.parseColor("#FF0055")
            tvStatus.text = "تنبيه: تم رصد شخص مجهول!"
            tvStatus.setTextColor(android.graphics.Color.parseColor("#FF0055"))
            statusIndicator.setBackgroundResource(R.drawable.bg_status_indicator)
            (statusIndicator.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                android.graphics.Color.parseColor("#FF0055")
            )

            tvName.text = "شخص غير معرف"
            tvJob.text = "غير مسجل في القاعدة"
            tvPhone.text = "مجهول"
            tvAddress.text = "تم التقاط الوجه في الخلفية"
            tvIdBadge.text = "UNKNOWN"
            tvConfidence.text = "${((1 - result.distance) * 100).toInt()}%"

            // عرض صورة الوجه المجهول الحالي
            result.faceBitmap?.let {
                ivProfile.setImageBitmap(it)
            }

            layoutUnknown.visibility = View.VISIBLE
            tvUnknownTimestamp.text = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            // لا نظهر البانر هنا، فقط عند الحفظ التلقائي
        }
    }

    private fun showUnknownWarning() {
        tvWarningBanner.visibility = View.VISIBLE
        tvWarningBanner.text = "⚠️ تنبيه: تم رصد شخص مجهول! تم تسجيل الوجه تلقائياً // ${System.currentTimeMillis()}"
        // إخفاء بعد 3 ثواني
        tvWarningBanner.postDelayed({
            tvWarningBanner.visibility = View.GONE
        }, 3000)
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
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "إذن الكاميرا مطلوب لعمل التطبيق", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceAnalyzer?.close()
        tfliteHelper.close()
        cameraProvider?.unbindAll()
    }
}
