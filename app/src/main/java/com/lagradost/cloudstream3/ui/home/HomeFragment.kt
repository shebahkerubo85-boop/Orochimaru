package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.ui.settings.Globals.TV

class HomeFragment : Fragment() {

    companion object {
        val configEvent = com.lagradost.cloudstream3.utils.Event<Boolean>()

        var currentSpan: Int = 1
            private set

        fun bindChips(chipGroup: ChipGroup, items: List<Pair<String, Boolean>>, callback: (Int) -> Unit) {}
        fun updateChips(chipGroup: ChipGroup, items: List<Pair<String, Boolean>>, callback: (Int) -> Unit) {}
        fun loadHomepageList(context: android.content.Context) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext())
    }
}
