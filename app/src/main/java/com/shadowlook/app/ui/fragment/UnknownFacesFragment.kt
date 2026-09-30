package com.shadowlook.app.ui.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.textfield.TextInputEditText
import com.shadowlook.app.R
import com.shadowlook.app.data.local.converters.Converters
import com.shadowlook.app.data.local.db.AppDatabase
import com.shadowlook.app.data.local.entity.UserFaceEntity
import com.shadowlook.app.ui.adapter.UnknownFacesAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class UnknownFacesFragment : Fragment() {

    private lateinit var adapter: UnknownFacesAdapter
    private val TAG = "UnknownFacesFragment"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return try {
            inflater.inflate(R.layout.fragment_unknown_faces, container, false)
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في onCreateView: ${e.message}", e)
            View(requireContext()).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvUnknownFaces)
            val emptyLayout = view.findViewById<View>(R.id.layoutEmptyUnknown)

            if (rv == null) {
                Log.e(TAG, "rvUnknownFaces not found")
                return
            }

            adapter = UnknownFacesAdapter(
                onDeleteClick = { entity ->
                    try {
                        AlertDialog.Builder(requireContext())
                            .setTitle("مسح السجل // DELETE_LOG")
                            .setMessage("هل تريد مسح سجل المجهول بتاريخ ${entity.formattedDate}؟")
                            .setPositiveButton("مسح") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        File(entity.imagePath).takeIf { it.exists() }?.delete()
                                        val db = AppDatabase.getDatabase(requireContext())
                                        db.unknownFaceDao().deleteUnknown(entity)
                                    } catch (e: Throwable) {
                                        Toast.makeText(requireContext(), "فشل المسح: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في مسح: ${e.message}", e)
                    }
                },
                onItemClick = { entity ->
                    // تحسين 3: عند الضغط على الوجه، إظهار لوحة لملء البيانات وتحويله إلى معروف مع تاريخ تلقائي
                    showConvertDialog(entity)
                }
            )

            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter

            lifecycleScope.launch {
                try {
                    val db = AppDatabase.getDatabase(requireContext())
                    db.unknownFaceDao().getAllUnknowns().collectLatest { list ->
                        try {
                            adapter.submitList(list)
                            emptyLayout?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        } catch (e: Throwable) {
                            Log.e(TAG, "خطأ في تحديث القائمة: ${e.message}", e)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "خطأ في مراقبة البيانات: ${e.message}", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في onViewCreated: ${e.message}", e)
        }
    }

    private fun showConvertDialog(entity: com.shadowlook.app.data.local.entity.UnknownFaceEntity) {
        try {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_convert_unknown, null)
            val ivPhoto = dialogView.findViewById<ImageView>(R.id.ivUnknownToConvert)
            val tvDate = dialogView.findViewById<TextView>(R.id.tvUnknownOriginalDate)
            val etName = dialogView.findViewById<TextInputEditText>(R.id.etConvertName)
            val etPhone = dialogView.findViewById<TextInputEditText>(R.id.etConvertPhone)
            val etJob = dialogView.findViewById<TextInputEditText>(R.id.etConvertJob)
            val etAddress = dialogView.findViewById<TextInputEditText>(R.id.etConvertAddress)

            // عرض صورة المجهول
            try {
                val file = File(entity.imagePath)
                if (file.exists()) {
                    ivPhoto.load(file)
                }
            } catch (e: Throwable) {
            }

            tvDate.text = "تاريخ الالتقاط: ${entity.formattedDate} // سيتم إضافة تاريخ جديد تلقائياً"

            AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton("تحويل إلى معروف // CONVERT") { _, _ ->
                    try {
                        val name = etName.text?.toString()?.trim() ?: ""
                        val phone = etPhone.text?.toString()?.trim() ?: ""
                        val job = etJob.text?.toString()?.trim() ?: ""
                        val address = etAddress.text?.toString()?.trim() ?: ""

                        if (name.isEmpty()) {
                            Toast.makeText(requireContext(), "الاسم مطلوب", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }

                        lifecycleScope.launch {
                            try {
                                // تحسين 3: تاريخ يضاف تلقائياً لكل صورة
                                val knownDir = File(requireContext().filesDir, "known_faces")
                                if (!knownDir.exists()) knownDir.mkdirs()

                                // نسخ صورة المجهول إلى مجلد المعروفين مع تاريخ جديد
                                val newFileName = "known_${System.currentTimeMillis()}.jpg"
                                val newImageFile = File(knownDir, newFileName)

                                // نسخ الملف
                                try {
                                    File(entity.imagePath).copyTo(newImageFile, overwrite = true)
                                } catch (e: Throwable) {
                                    // إذا فشل النسخ، استخدم المسار الأصلي
                                    Log.e(TAG, "فشل نسخ الصورة: ${e.message}", e)
                                }

                                // إنشاء كيان معروف جديد مع تاريخ تلقائي
                                val knownEntity = UserFaceEntity(
                                    name = name,
                                    phone = phone,
                                    jobTitle = job,
                                    address = address,
                                    imagePath = if (newImageFile.exists()) newImageFile.absolutePath else entity.imagePath,
                                    vectorEmbedding = entity.vectorEmbedding, // نفس الـ embedding
                                    timestamp = System.currentTimeMillis(), // تاريخ جديد تلقائي
                                    formattedDate = UserFaceEntity.getCurrentFormattedDate() // تاريخ منسق تلقائي
                                )

                                val db = AppDatabase.getDatabase(requireContext())
                                db.userFaceDao().insertKnown(knownEntity)

                                // تحسين: لا نحذف المجهول بعد التحويل - نحتفظ به كنسخة احتياطية
                                // فقط ننسخ الصورة، لا نحذف الأصل - للحفاظ على قاعدة البيانات
                                try {
                                    // لا نحذف - نحتفظ بالمجهول الأصلي
                                    // db.unknownFaceDao().deleteUnknown(entity)
                                    // File(entity.imagePath).takeIf { it.exists() }?.delete()
                                    Log.d(TAG, "تم الاحتفاظ بالمجهول الأصلي بعد التحويل")
                                } catch (e: Throwable) {
                                }

                                Toast.makeText(requireContext(), "✅ تم تحويل ${entity.formattedDate} إلى معروف: $name - تم الاحتفاظ بالنسخة الأصلية في المجهولين", Toast.LENGTH_LONG).show()

                            } catch (e: Throwable) {
                                Log.e(TAG, "فشل التحويل: ${e.message}", e)
                                Toast.makeText(requireContext(), "فشل التحويل: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }

                    } catch (e: Throwable) {
                        Log.e(TAG, "خطأ في تحويل: ${e.message}", e)
                    }
                }
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("عرض الصورة") { _, _ ->
                    try {
                        // عرض الصورة بحجم كبير
                        val imageView = ImageView(requireContext()).apply {
                            load(File(entity.imagePath))
                        }
                        AlertDialog.Builder(requireContext())
                            .setView(imageView)
                            .setPositiveButton("إغلاق", null)
                            .show()
                    } catch (e: Throwable) {
                    }
                }
                .show()

        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في showConvertDialog: ${e.message}", e)
            Toast.makeText(requireContext(), "خطأ في فتح لوحة التحويل: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
