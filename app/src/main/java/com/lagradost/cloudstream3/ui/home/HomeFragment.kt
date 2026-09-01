package com.lagradost.cloudstream3.ui.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.utils.Event

class HomeFragment : Fragment() {

    companion object {
        val configEvent = Event<Boolean>()

        var currentSpan: Int = 1
        val errorProfilePic: Int = 0

        suspend fun expandAndReturn(name: String): Any? { return null }

        @JvmOverloads
        fun bindChips(chipGroup: Any?, items: Any, types: Any? = null, callback: Any? = null, nextFocusDown: Any? = null, nextFocusUp: Any? = null) {}
        @JvmOverloads
        fun updateChips(chipGroup: Any?, items: Any, callback: Any? = null) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext())
    }
}

fun Activity.loadHomepageList(
    item: Any,
    dismissCallback: (() -> Unit)? = null,
    expandCallback: ((String) -> Unit)? = null
): Any? { return null }
