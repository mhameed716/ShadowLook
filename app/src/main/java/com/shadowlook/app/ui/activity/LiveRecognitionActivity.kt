package com.shadowlook.app.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.card.MaterialCardView
import com.shadowlook.app.R
import com.shadowlook.app.ml.FaceAnalyzer
import com.shadowlook.app.ml.TFLiteHelper
import com.shadowlook.app.ui.adapter.UnknownSimilarityAdapter
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
    private lateinit var layoutSimilarUnknowns: View
    private lateinit var rvSimilarUnknowns: RecyclerView
    private lateinit var similarityAdapter: UnknownSimilarityAdapter

    private lateinit var cameraExecutor: ExecutorService
    private var tfliteHelper: TFLiteHelper? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var isNightVisionEnabled = false
    private var isFrontCamera = true

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
            setupSimilarUnknownsRecycler()

            try {
                tfliteHelper = TFLiteHelper(this)
                Log.d(TAG, "TFLiteHelper ready: ${tfliteHelper?.isModelReady()}")
            } catch (e: Throwable) {
                Log.e(TAG, "TFLiteHelper error: ${e.message}", e)
                tfliteHelper = TFLiteHelper(this)
            }

            cameraExecutor = Executors.newSingleThreadExecutor()

            if (allPermissionsGranted()) {
                startCameraSafely()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }

        } catch (e: Throwable) {
            Log.e(TAG, "Critical onCreate error: ${e.message}", e)
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            try {
                setContentView(R.layout.activity_live_recognition)
                initViewsSafe()
            } catch (e2: Throwable) {
                finish()
            }
        }
    }

    private fun initViewsSafe() {
        try {
            previewView = findViewById(R.id.previewView) ?: throw IllegalStateException("previewView not found")
            overlayView = findViewById(R.id.overlayView) ?: throw IllegalStateException("overlayView not found")

            val includeView = findViewById<View>(R.id.cyberCardInclude)
                ?: throw IllegalStateException("cyberCardInclude not found")

            cyberCard = includeView
            cardView = includeView as? MaterialCardView
                ?: findViewById(R.id.cyberCardInclude) as? MaterialCardView
                ?: throw IllegalStateException("cardView null")

            tvStatus = includeView.findViewById(R.id.tvStatus) ?: throw IllegalStateException("tvStatus not found")
            tvName = includeView.findViewById(R.id.tvName) ?: throw IllegalStateException("tvName not found")
            tvJob = includeView.findViewById(R.id.tvJobTitle) ?: throw IllegalStateException("tvJobTitle not found")
            tvPhone = includeView.findViewById(R.id.tvPhone) ?: throw IllegalStateException("tvPhone not found")
            tvAddress = includeView.findViewById(R.id.tvAddress) ?: throw IllegalStateException("tvAddress not found")
            tvConfidence = includeView.findViewById(R.id.tvConfidence) ?: throw IllegalStateException("tvConfidence not found")
            tvIdBadge = includeView.findViewById(R.id.tvIdBadge) ?: throw IllegalStateException("tvIdBadge not found")
            ivProfile = includeView.findViewById(R.id.ivProfilePhoto) ?: throw IllegalStateException("ivProfilePhoto not found")
            layoutUnknown = includeView.findViewById(R.id.layoutUnknownAlert) ?: throw IllegalStateException("layoutUnknownAlert not found")
            tvUnknownTimestamp = includeView.findViewById(R.id.tvUnknownTimestamp) ?: throw IllegalStateException("tvUnknownTimestamp not found")
            statusIndicator = includeView.findViewById(R.id.statusIndicator) ?: throw IllegalStateException("statusIndicator not found")

            tvWarningBanner = findViewById(R.id.tvUnknownWarningBanner) ?: throw IllegalStateException("tvUnknownWarningBanner not found")

            tvQualityInfo = includeView.findViewById(R.id.tvQualityInfo) ?: throw IllegalStateException("tvQualityInfo not found")
            tvSpeedInfo = includeView.findViewById(R.id.tvSpeedInfo) ?: throw IllegalStateException("tvSpeedInfo not found")
            tvScanningText = findViewById(R.id.tvScanningText) ?: throw IllegalStateException("tvScanningText not found")

            layoutSimilarUnknowns = findViewById(R.id.layoutSimilarUnknowns) ?: throw IllegalStateException("layoutSimilarUnknowns not found")
            rvSimilarUnknowns = findViewById(R.id.rvSimilarUnknowns) ?: throw IllegalStateException("rvSimilarUnknowns not found")

            cyberCard.visibility = View.GONE
            tvWarningBanner.visibility = View.GONE
            layoutSimilarUnknowns.visibility = View.GONE

            Log.d(TAG, "✅ All views bound - v2.1 with auto detection, similarity, 3D, tracking, 3m distance")

        } catch (e: Throwable) {
            Log.e(TAG, "❌ initViewsSafe error: ${e.message}", e)
            throw e
        }
    }

    private fun setupSimilarUnknownsRecycler() {
        try {
            similarityAdapter = UnknownSimilarityAdapter()
            // تحسين 3: عمودين من الصور
            rvSimilarUnknowns.layoutManager = GridLayoutManager(this, 2, GridLayoutManager.HORIZONTAL, false)
            rvSimilarUnknowns.adapter = similarityAdapter
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في setupSimilarUnknownsRecycler: ${e.message}", e)
        }
    }

    private fun setupClickListeners() {
        try {
            findViewById<View>(R.id.btnOpenRegister)?.setOnClickListener {
                try {
                    startActivity(Intent(this, RegisterFaceActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح التسجيل: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnOpenDatabase)?.setOnClickListener {
                try {
                    startActivity(Intent(this, DatabaseActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح القاعدة: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnOpenDashboard)?.setOnClickListener {
                try {
                    startActivity(Intent(this, DashboardActivity::class.java))
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل فتح الإحصائيات: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnSwitchCamera)?.setOnClickListener {
                try {
                    switchCamera()
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل تبديل الكاميرا: ${e.message}")
                }
            }
            findViewById<View>(R.id.btnNightVision)?.setOnClickListener {
                try {
                    toggleNightVision()
                } catch (e: Throwable) {
                    showErrorDialog("خطأ", "فشل الرؤية الليلية: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "setupClickListeners error: ${e.message}", e)
        }
    }

    private fun switchCamera() {
        try {
            isFrontCamera = !isFrontCamera
            cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSwitchCamera)
            btn?.text = if (isFrontCamera) "أمامية" else "خلفية"
            Log.d(TAG, "تبديل إلى ${if (isFrontCamera) "الأمامية" else "الخلفية"}")
            bindCameraUseCasesSafely()
            showWarningToast("كاميرا ${if (isFrontCamera) "أمامية" else "خلفية"}")
        } catch (e: Throwable) {
            Log.e(TAG, "switchCamera error: ${e.message}", e)
        }
    }

    private fun toggleNightVision() {
        try {
            isNightVisionEnabled = !isNightVisionEnabled
            val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNightVision)
            
            // تحديث FaceAnalyzer أيضاً
            faceAnalyzer?.setNightVisionMode(isNightVisionEnabled)
            
            if (isNightVisionEnabled) {
                try {
                    if (!isFrontCamera) {
                        camera?.cameraControl?.enableTorch(true)
                    }
                    btn?.text = "ليلي ON"
                    showWarningToast("🌙 رؤية ليلية ON - يتعرف في الظلام حتى على بعد قريب جداً")
                } catch (e: Throwable) {
                    btn?.text = "ليلي ON"
                    showWarningToast("🌙 رؤية ليلية برمجية نشطة - تحسين تلقائي في الظلام")
                }
            } else {
                try {
                    camera?.cameraControl?.enableTorch(false)
                } catch (e: Throwable) {}
                btn?.text = "ليلي"
                showWarningToast("تم إيقاف الرؤية الليلية")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "toggleNightVision error: ${e.message}", e)
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
                    Log.e(TAG, "CameraProvider error: ${e.message}", e)
                    showErrorDialog("خطأ في الكاميرا", "فشل تهيئة الكاميرا: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Throwable) {
            Log.e(TAG, "startCameraSafely error: ${e.message}", e)
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
                            Log.e(TAG, "handleFaceResult error: ${e.message}", e)
                        }
                    }
                },
                onUnknownFaceDetected = { bitmap, embedding, similarUnknowns ->
                    runOnUiThread {
                        try {
                            showUnknownWarning()
                            // تحسين 3: عرض الصور المشابهة في عمودين مع نسبة التشابه
                            if (similarUnknowns.isNotEmpty()) {
                                layoutSimilarUnknowns.visibility = View.VISIBLE
                                similarityAdapter.submitList(similarUnknowns)
                                Log.d(TAG, "عرض ${similarUnknowns.size} وجوه مجهولة مشابهة")
                            } else {
                                layoutSimilarUnknowns.visibility = View.GONE
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "onUnknownFaceDetected error: ${e.message}", e)
                        }
                    }
                },
                onNoFaceDetected = {
                    runOnUiThread {
                        try {
                            cyberCard.visibility = View.GONE
                            layoutSimilarUnknowns.visibility = View.GONE
                            tvScanningText.text = "🔍 جاري البحث... // SCANNING"
                            tvScanningText.setTextColor(android.graphics.Color.parseColor("#00FF66"))
                        } catch (e: Throwable) {
                        }
                    }
                }
            )

            imageAnalysis.setAnalyzer(cameraExecutor, faceAnalyzer!!)

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            
            // تحسين 1: إذا الرؤية الليلية مفعلة وكان لدينا كاميرا خلفية، فعل الفلاش
            if (isNightVisionEnabled && !isFrontCamera) {
                try {
                    camera?.cameraControl?.enableTorch(true)
                } catch (e: Throwable) {}
            }
            
            Log.d(TAG, "✅ Camera bound - ${if (isFrontCamera) "Front" else "Back"} - Auto detection, 3m distance, 3D tracking, Night vision: $isNightVisionEnabled")

        } catch (e: Throwable) {
            Log.e(TAG, "bindCameraUseCases error: ${e.message}", e)
            showErrorDialog("خطأ في الكاميرا", "فشل ربط الكاميرا: ${e.message}")
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
                // معروف: أخضر مع واجهة منبثقة جميلة ومرتبة مع صورته المحفوظة
                cardView.strokeColor = android.graphics.Color.parseColor("#00FF66")
                tvStatus.text = "✅ ${result.userName} // ${String.format("%.1f", result.similarityPercent)}%"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#00FF66"))
                try {
                    (statusIndicator.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                        android.graphics.Color.parseColor("#00FF66")
                    )
                } catch (e: Throwable) {}

                tvName.text = result.userName ?: "مستخدم معروف"
                tvJob.text = result.jobTitle ?: "غير محدد"
                tvPhone.text = result.phone ?: "---"
                tvAddress.text = result.address ?: "لا يوجد عنوان"
                tvIdBadge.text = "ID: ${result.userId?.toString()?.padStart(3, '0') ?: "---"}"

                tvConfidence.text = "${String.format("%.1f", result.similarityPercent)}%"

                try {
                    tvQualityInfo.text = "جودة: ${(result.quality * 100).toInt()}% | 3D: ${result.headEulerY.toInt()}°"
                    tvSpeedInfo.text = "تشابه: ${String.format("%.1f", result.similarityPercent)}% | ${String.format("%.2f", result.distance)}"
                    tvScanningText.text = "✅ ${result.userName} - ${String.format("%.1f", result.similarityPercent)}% - تتبع: ${result.trackingId ?: "-"}"
                    tvScanningText.setTextColor(android.graphics.Color.parseColor("#00FF66"))
                } catch (e: Throwable) {}

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
                layoutSimilarUnknowns.visibility = View.GONE

            } else {
                // مجهول: أحمر مع التقاط صورة
                cardView.strokeColor = android.graphics.Color.parseColor("#FF0055")
                tvStatus.text = "⚠️ مجهول // ${String.format("%.1f", result.similarityPercent)}%"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FF0055"))
                try {
                    (statusIndicator.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                        android.graphics.Color.parseColor("#FF0055")
                    )
                } catch (e: Throwable) {}

                tvName.text = "شخص غير معرف"
                tvJob.text = "غير مسجل"
                tvPhone.text = "تشابه: ${String.format("%.1f", result.similarityPercent)}%"
                tvAddress.text = "تم التقاط صورة تلقائياً"
                tvIdBadge.text = "UNKNOWN"
                tvConfidence.text = "${String.format("%.1f", result.similarityPercent)}%"

                try {
                    tvQualityInfo.text = "جودة: ${(result.quality * 100).toInt()}% | 3D: ${result.headEulerY.toInt()}°"
                    tvSpeedInfo.text = "التقاط: تلقائي | مسافة: ${String.format("%.2f", result.distance)}"
                    tvScanningText.text = "⚠️ مجهول - تشابه ${String.format("%.1f", result.similarityPercent)}% - تتبع: ${result.trackingId ?: "-"}"
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
            Log.e(TAG, "handleFaceResult error: ${e.message}", e)
        }
    }

    private fun showWarningToast(message: String) {
        try {
            runOnUiThread {
                try {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    // أيضاً عرض في banner
                    tvWarningBanner.visibility = View.VISIBLE
                    tvWarningBanner.text = message
                    tvWarningBanner.postDelayed({
                        try {
                            tvWarningBanner.visibility = View.GONE
                        } catch (e: Throwable) {}
                    }, 2500)
                } catch (e: Throwable) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
        }
    }

    private fun showUnknownWarning() {
        try {
            tvWarningBanner.visibility = View.VISIBLE
            tvWarningBanner.text = "⚠️ مجهول مرصود - تم التقاط صورة - جاري البحث في المجهولين..."
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
