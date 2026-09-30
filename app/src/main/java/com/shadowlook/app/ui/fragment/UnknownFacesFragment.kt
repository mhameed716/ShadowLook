package com.shadowlook.app.ui.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.shadowlook.app.R
import com.shadowlook.app.data.local.db.AppDatabase
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

            adapter = UnknownFacesAdapter { entity ->
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
                                    Toast.makeText(requireContext(), "تم المسح", Toast.LENGTH_SHORT).show()
                                } catch (e: Throwable) {
                                    Log.e(TAG, "فشل المسح: ${e.message}", e)
                                    Toast.makeText(requireContext(), "فشل المسح: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                } catch (e: Throwable) {
                    Log.e(TAG, "خطأ في مسح: ${e.message}", e)
                }
            }

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
}
