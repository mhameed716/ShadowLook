package com.shadowlook.app.ui.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_known_faces, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvKnownFaces)
        val emptyLayout = view.findViewById<View>(R.id.layoutEmptyKnown)

        adapter = KnownFacesAdapter { entity ->
            // حذف مع تأكيد
            AlertDialog.Builder(requireContext())
                .setTitle("حذف الملف // DELETE_PROFILE")
                .setMessage("هل أنت متأكد من حذف ملف ${entity.name}؟ سيتم حذف الصورة والبيانات نهائياً.")
                .setPositiveButton("حذف") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            // حذف الصورة من التخزين
                            File(entity.imagePath).takeIf { it.exists() }?.delete()
                            // حذف من DB
                            val db = AppDatabase.getDatabase(requireContext())
                            db.userFaceDao().deleteKnown(entity)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(requireContext(), "فشل الحذف: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // مراقبة البيانات
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            if (searchQuery.isEmpty()) {
                db.userFaceDao().getAllKnowns().collectLatest { list ->
                    adapter.submitList(list)
                    emptyLayout.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            } else {
                db.userFaceDao().searchKnowns(searchQuery).collectLatest { list ->
                    adapter.submitList(list)
                    emptyLayout.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
        // إعادة تحميل
        view?.let { onViewCreated(it, null) }
    }

    fun updateSearch(query: String) {
        searchQuery = query
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val flow = if (query.isEmpty()) db.userFaceDao().getAllKnowns() else db.userFaceDao().searchKnowns(query)
            flow.collectLatest { list ->
                adapter.submitList(list)
                view?.findViewById<View>(R.id.layoutEmptyKnown)?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
