package com.lagradost.cloudstream3.ui.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.MainAPI.TvType
import com.lagradost.cloudstream3.utils.Event

class HomeFragment : Fragment() {

    companion object {
        val configEvent = Event<Boolean>()

        var currentSpan: Int = 1
        val errorProfilePic: Int = 0

        @JvmStatic
        fun bindChips(
            chipGroup: ChipGroup?,
            selectedTypes: List<TvType>,
            availableTypes: List<TvType>,
            callback: (List<TvType>) -> Unit,
            nextFocusDown: Int = 0,
            nextFocusUp: Int = 0
        ) {}

        @JvmStatic
        fun updateChips(
            chipGroup: ChipGroup?,
            selectedTypes: List<TvType>,
            callback: (List<TvType>) -> Unit
        ) {}

        @JvmStatic
        fun expandAndReturn(name: String): Any? { return null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext())
    }
}

fun Activity.loadHomepageList(
    item: Any,
    dismissCallback: (() -> Unit)? = null,
    expandCallback: (suspend (String) -> Any?)? = null
): BottomSheetDialog? { return null }
