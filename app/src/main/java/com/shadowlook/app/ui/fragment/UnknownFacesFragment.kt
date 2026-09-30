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
import com.shadowlook.app.ui.adapter.UnknownFacesAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class UnknownFacesFragment : Fragment() {

    private lateinit var adapter: UnknownFacesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_unknown_faces, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvUnknownFaces)
        val emptyLayout = view.findViewById<View>(R.id.layoutEmptyUnknown)

        adapter = UnknownFacesAdapter { entity ->
            AlertDialog.Builder(requireContext())
                .setTitle("مسح السجل // DELETE_LOG")
                .setMessage("هل تريد مسح سجل المجهول بتاريخ ${entity.formattedDate}؟")
                .setPositiveButton("مسح") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            File(entity.imagePath).takeIf { it.exists() }?.delete()
                            val db = AppDatabase.getDatabase(requireContext())
                            db.unknownFaceDao().deleteUnknown(entity)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(requireContext(), "فشل المسح: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            db.unknownFaceDao().getAllUnknowns().collectLatest { list ->
                adapter.submitList(list)
                emptyLayout.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
