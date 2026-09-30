package com.shadowlook.app.ui.activity

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.shadowlook.app.R
import com.shadowlook.app.data.local.db.AppDatabase
import com.shadowlook.app.ui.fragment.KnownFacesFragment
import com.shadowlook.app.ui.fragment.UnknownFacesFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DatabaseActivity : AppCompatActivity() {

    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var etSearch: com.google.android.material.textfield.TextInputEditText
    private lateinit var tvKnownCount: TextView
    private lateinit var tvUnknownCount: TextView
    private lateinit var tvDatabasePath: TextView

    private val knownFragment = KnownFacesFragment()
    private val unknownFragment = UnknownFacesFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_database)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        etSearch = findViewById(R.id.etSearch)
        tvKnownCount = findViewById(R.id.tvKnownCount)
        tvUnknownCount = findViewById(R.id.tvUnknownCount)
        tvDatabasePath = findViewById(R.id.tvDatabasePath)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // ViewPager Adapter - واجهة قاعدة البيانات
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) = when (position) {
                0 -> knownFragment
                else -> unknownFragment
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "المعروفون // KNOWN"
                else -> "المجهولون // UNKNOWN"
            }
        }.attach()

        // تحميل إحصائيات قاعدة البيانات - إظهار أن القاعدة موجودة ومحمية
        loadDatabaseStats()

        // Search logic - only for known tab
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (viewPager.currentItem == 0) {
                    knownFragment.updateSearch(s?.toString() ?: "")
                }
            }
        })

        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 0) {
                    etSearch.isEnabled = true
                    etSearch.hint = "بحث بالاسم أو رقم الهاتف..."
                } else {
                    etSearch.isEnabled = false
                    etSearch.hint = "البحث متاح فقط في تبويب المعروفين"
                }
            }
        })

        // عرض مسار قاعدة البيانات
        try {
            val dbPath = getDatabasePath("shadowlook_database")
            tvDatabasePath.text = "قاعدة البيانات: ${dbPath.absolutePath} - محمية من الحذف ✅ v2.7"
        } catch (e: Exception) {
            tvDatabasePath.text = "قاعدة البيانات: shadowlook_database - محمية من الحذف ✅ v2.7 - موجودة"
        }
    }

    private fun loadDatabaseStats() {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@DatabaseActivity)
                
                // مراقبة عدد المعروفين
                launch {
                    try {
                        db.userFaceDao().getAllKnowns().collectLatest { list ->
                            tvKnownCount.text = list.size.toString()
                            Log.d("DatabaseActivity", "قاعدة البيانات: المعروفون = ${list.size}")
                        }
                    } catch (e: Exception) {
                        Log.e("DatabaseActivity", "خطأ في تحميل المعروفين: ${e.message}", e)
                        tvKnownCount.text = "خطأ"
                    }
                }

                // مراقبة عدد المجهولين
                launch {
                    try {
                        db.unknownFaceDao().getAllUnknowns().collectLatest { list ->
                            tvUnknownCount.text = list.size.toString()
                            Log.d("DatabaseActivity", "قاعدة البيانات: المجهولون = ${list.size}")
                        }
                    } catch (e: Exception) {
                        Log.e("DatabaseActivity", "خطأ في تحميل المجهولين: ${e.message}", e)
                        tvUnknownCount.text = "خطأ"
                    }
                }

            } catch (e: Exception) {
                Log.e("DatabaseActivity", "خطأ في تحميل إحصائيات قاعدة البيانات: ${e.message}", e)
            }
        }
    }
}
