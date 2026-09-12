package ani.sanin.home

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ani.sanin.R
import ani.sanin.databinding.BottomSheetLibrarySettingsBinding
import ani.sanin.getThemeColor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class LibrarySettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLibrarySettingsBinding? = null
    private val binding get() = _binding!!

    private var onSortChanged: ((String) -> Unit)? = null
    private var onGenreFilterChanged: ((String) -> Unit)? = null
    private var onNsfwChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val TAG = "LibrarySettingsBottomSheet"

        fun newInstance(
            currentSort: String,
            filterItems: List<String>? = null,
            showNsfw: Boolean = true,
            onSortChanged: (String) -> Unit,
            onGenreFilterChanged: (String) -> Unit,
            onNsfwChanged: ((Boolean) -> Unit)? = null
        ): LibrarySettingsBottomSheet {
            return LibrarySettingsBottomSheet().apply {
                this.onSortChanged = onSortChanged
                this.onGenreFilterChanged = onGenreFilterChanged
                this.onNsfwChanged = onNsfwChanged
                arguments = Bundle().apply {
                    putString("sort", currentSort)
                    putBoolean("showNsfw", showNsfw)
                    putStringArrayList("filters", ArrayList(filterItems ?: emptyList()))
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetLibrarySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentSort = arguments?.getString("sort") ?: "updatedAt"
        val showNsfw = arguments?.getBoolean("showNsfw") ?: true

        // NSFW toggle visibility
        if (!showNsfw || onNsfwChanged == null) {
            binding.nsfwToggle.visibility = View.GONE
        } else {
            binding.nsfwToggle.isChecked = PrefManager.getVal<Boolean>(PrefName.LibraryNsfw)
            FocusEffectUtil.applyFocusListener(binding.nsfwToggle)
            binding.nsfwToggle.setOnCheckedChangeListener { _, isChecked ->
                PrefManager.setVal(PrefName.LibraryNsfw, isChecked)
                onNsfwChanged?.invoke(isChecked)
            }
        }

        // Sort buttons
        val sortButtons = mapOf(
            binding.sortRecent to "updatedAt",
            binding.sortScore to "score",
            binding.sortTitle to "title",
            binding.sortRelease to "release"
        )
        updateSortHighlight(currentSort)
        sortButtons.forEach { (btn, sortKey) ->
            FocusEffectUtil.applyFocusListener(btn)
            btn.setOnClickListener {
                PrefManager.setVal(PrefName.AnimeListSortOrder, sortKey)
                updateSortHighlight(sortKey)
                onSortChanged?.invoke(sortKey)
                dismiss()
            }
        }

        // Genre filter button (anime: genres; movie: statuses provided by the fragment)
        FocusEffectUtil.applyFocusListener(binding.genreFilterBtn)
        binding.genreFilterBtn.setOnClickListener {
            showFilterDialog()
        }
    }

    private fun showFilterDialog() {
        val items = mutableListOf("All") + genres()
        val current = binding.genreFilterBtn.text.toString()
        val checked = items.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(requireContext(), R.style.MyPopup)
            .setTitle("Filter by Genre")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                val selected = items[which]
                binding.genreFilterBtn.text = selected
                PrefManager.setVal(PrefName.LibraryGenreFilter, if (selected == "All") "" else selected)
                onGenreFilterChanged?.invoke(selected)
                dialog.dismiss()
            }
            .show()
    }

    /** Filter items: genres for anime mode, status categories for movie mode. */
    private fun genres(): List<String> {
        arguments?.getStringArrayList("filters")?.takeIf { it.isNotEmpty() }?.let { return it }
        val stored = PrefManager.getVal<Set<String>>(PrefName.GenresList)
        return if (stored.isNullOrEmpty()) {
            listOf(
                "Completed Movies", "Completed TV", "Watching",
                "Planning", "Paused", "Dropped", "Favourites"
            )
        } else {
            stored.toList().sorted()
        }
    }

    private fun updateSortHighlight(activeKey: String) {
        val mapping = mapOf(
            binding.sortRecent to "updatedAt",
            binding.sortScore to "score",
            binding.sortTitle to "title",
            binding.sortRelease to "release"
        )
        val ctx = requireContext()
        mapping.forEach { (btn, key) ->
            val active = key == activeKey
            btn.backgroundTintList = if (active) {
                ColorStateList.valueOf(ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary))
            } else {
                null
            }
            btn.setTextColor(if (active) Color.WHITE else ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
