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
import com.shadowlook.app.ui.adapter.KnownFacesAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class KnownFacesFragment : Fragment() {

    private lateinit var adapter: KnownFacesAdapter
    private var searchQuery: String = ""
    private val TAG = "KnownFacesFragment"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return try {
            inflater.inflate(R.layout.fragment_known_faces, container, false)
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في onCreateView: ${e.message}", e)
            // Fallback view
            View(requireContext()).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvKnownFaces)
            val emptyLayout = view.findViewById<View>(R.id.layoutEmptyKnown)

            if (rv == null) {
                Log.e(TAG, "rvKnownFaces not found in layout")
                return
            }

            adapter = KnownFacesAdapter { entity ->
                try {
                    AlertDialog.Builder(requireContext())
                        .setTitle("حذف الملف // DELETE_PROFILE")
                        .setMessage("هل أنت متأكد من حذف ملف ${entity.name}؟ سيتم حذف الصورة والبيانات نهائياً.")
                        .setPositiveButton("حذف") { _, _ ->
                            lifecycleScope.launch {
                                try {
                                    File(entity.imagePath).takeIf { it.exists() }?.delete()
                                    val db = AppDatabase.getDatabase(requireContext())
                                    db.userFaceDao().deleteKnown(entity)
                                    Toast.makeText(requireContext(), "تم الحذف", Toast.LENGTH_SHORT).show()
                                } catch (e: Throwable) {
                                    Log.e(TAG, "فشل الحذف: ${e.message}", e)
                                    Toast.makeText(requireContext(), "فشل الحذف: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                } catch (e: Throwable) {
                    Log.e(TAG, "خطأ في حذف: ${e.message}", e)
                }
            }

            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter

            lifecycleScope.launch {
                try {
                    val db = AppDatabase.getDatabase(requireContext())
                    val flow = if (searchQuery.isEmpty()) db.userFaceDao().getAllKnowns() else db.userFaceDao().searchKnowns(searchQuery)
                    flow.collectLatest { list ->
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

    fun updateSearch(query: String) {
        try {
            searchQuery = query
            lifecycleScope.launch {
                try {
                    val db = AppDatabase.getDatabase(requireContext())
                    val flow = if (query.isEmpty()) db.userFaceDao().getAllKnowns() else db.userFaceDao().searchKnowns(query)
                    flow.collectLatest { list ->
                        try {
                            adapter.submitList(list)
                            view?.findViewById<View>(R.id.layoutEmptyKnown)?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                        } catch (e: Throwable) {
                            Log.e(TAG, "خطأ في updateSearch: ${e.message}", e)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "خطأ في updateSearch: ${e.message}", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ في updateSearch outer: ${e.message}", e)
        }
    }
}
