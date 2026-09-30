package com.shadowlook.app.ui.activity

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.shadowlook.app.R
import com.shadowlook.app.ui.fragment.KnownFacesFragment
import com.shadowlook.app.ui.fragment.UnknownFacesFragment

class DatabaseActivity : AppCompatActivity() {

    private lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var tabLayout: com.google.android.material.tabs.TabLayout
    private lateinit var etSearch: com.google.android.material.textfield.TextInputEditText

    private val knownFragment = KnownFacesFragment()
    private val unknownFragment = UnknownFacesFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_database)

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)
        etSearch = findViewById(R.id.etSearch)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // ViewPager Adapter
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) = when (position) {
                0 -> knownFragment
                else -> unknownFragment
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "الأشخاص المسجلون"
                else -> "المجهولون المرصودون"
            }
        }.attach()

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
                    etSearch.hint = "البحث متاح فقط في تبويب المسجلين"
                }
            }
        })
    }
}
