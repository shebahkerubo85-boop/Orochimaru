package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.Event

class HomeFragment : Fragment() {

    companion object {
        val configEvent = Event<Boolean>()

        var currentSpan: Int = 1
        val errorProfilePic: Int = 0

        @JvmOverloads
        fun bindChips(chipGroup: ChipGroup, items: List<*>, callback: ((Int) -> Unit)? = null, list: List<Int> = emptyList()) {}
        @JvmOverloads
        fun updateChips(chipGroup: ChipGroup, items: List<*>, callback: ((Int) -> Unit)? = null) {}
        fun loadHomepageList(context: android.content.Context) {}
        suspend fun expandAndReturn(name: String): Any? { return null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext())
    }
}
