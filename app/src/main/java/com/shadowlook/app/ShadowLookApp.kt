package com.shadowlook.app

import android.app.Application
import android.util.Log
import kotlin.system.exitProcess

class ShadowLookApp : Application() {

    companion object {
        private const val TAG = "ShadowLookApp"
    }

    override fun onCreate() {
        super.onCreate()

        // معالج الأخطاء العام لمنع انهيار التطبيق
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "❌ خطأ غير معالج في Thread ${thread.name}: ${throwable.message}", throwable)

                // تسجيل الخطأ في ملف
                try {
                    val logFile = java.io.File(filesDir, "crash_log.txt")
                    logFile.appendText(
                        """
                        |--- Crash at ${java.util.Date()} ---
                        |Thread: ${thread.name}
                        |Error: ${throwable.message}
                        |Stack: ${throwable.stackTraceToString()}
                        |Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} - API ${android.os.Build.VERSION.SDK_INT}
                        |ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}
                        |----------------------------------------
                        |
                        """.trimMargin()
                    )
                } catch (e: Throwable) {
                }

                // إذا كان الخطأ في مكتبات TFLite، لا تنهي التطبيق، فقط سجل الخطأ
                val isTFLiteError = throwable.message?.contains("tensorflow", ignoreCase = true) == true ||
                        throwable.message?.contains("tflite", ignoreCase = true) == true ||
                        throwable.stackTraceToString().contains("tensorflow", ignoreCase = true)

                val isUnsatisfiedLink = throwable is UnsatisfiedLinkError ||
                        throwable.cause is UnsatisfiedLinkError

                if (isTFLiteError || isUnsatisfiedLink) {
                    Log.w(TAG, "⚠️ خطأ في مكتبات TFLite - سيتم المتابعة في وضع المحاكاة")
                    // لا تنهي التطبيق، فقط سجل
                    return@setDefaultUncaughtExceptionHandler
                }

                // للأخطاء الأخرى، استدعاء المعالج الافتراضي
                defaultHandler?.uncaughtException(thread, throwable)

            } catch (e: Throwable) {
                Log.e(TAG, "خطأ في معالج الأخطاء العام: ${e.message}", e)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        Log.d(TAG, "✅ ShadowLookApp initialized - Device: ${android.os.Build.MODEL}, ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
    }
}
