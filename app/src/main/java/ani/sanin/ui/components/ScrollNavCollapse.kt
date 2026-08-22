package ani.sanin.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import android.widget.ScrollView

fun View?.findFirstScrollable(): View? {
    if (this == null) return null
    if (this is RecyclerView || this is ScrollView || this is NestedScrollView) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val r = getChildAt(i).findFirstScrollable()
            if (r != null) return r
        }
    }
    return null
}

fun attachNavScrollCollapse(scroll: View?, onCollapse: (Boolean) -> Unit) {
    if (scroll == null) return
    val density = scroll.resources.displayMetrics.density
    val threshold = (12 * density).toInt()
    if (scroll is RecyclerView) {
        scroll.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                onCollapse(rv.computeVerticalScrollOffset() > threshold)
            }
        })
    } else {
        scroll.setOnScrollChangeListener { _: View, _: Int, scrollY: Int, _: Int, _: Int ->
            onCollapse(scrollY > threshold)
        }
    }
}
