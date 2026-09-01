package com.lagradost.cloudstream3.ui.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import ani.sanin.databinding.TvtypesChipsBinding
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Event

class HomeFragment : Fragment() {

    companion object {
        val configEvent = Event<Boolean>()

        var currentSpan: Int = 1
        val errorProfilePic: Int = 0

        @JvmStatic
        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit
        ) {
            bindChips(header, selectedTypes, validTypes, callback, null, null)
        }

        @JvmStatic
        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit,
            nextFocusDown: Int?,
            nextFocusUp: Int?
        ) {}

        @JvmStatic
        fun updateChips(header: TvtypesChipsBinding?, selectedTypes: List<TvType>) {}

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
