package ani.sanin.media

import android.view.View
import android.widget.RadioGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * How the Franchise row is ordered.
 *
 * The names live in `strings.xml` rather than here, so the pill, the dropdown and the option
 * list cannot drift apart or need translating separately.
 */
enum class FranchiseSort {
    RANDOM,
    TRENDING,
    POPULAR,
    ALPHABETICAL,

    /**
     * The curated list cards, exactly as the row used to show them before real franchises.
     *
     * Kept as a sort rather than removed so the old content stays reachable: picking it swaps
     * the row back to Trakt's lists (IMDb Popular, Top Horror, ...), and any other sort shows
     * the real franchises instead.
     */
    COLLECTIONS,
    ;

    /** What the sort pill and the dropdown option show for this sort. */
    fun labelRes() = when (this) {
        RANDOM -> R.string.franchise_sort_random
        TRENDING -> R.string.franchise_sort_trending
        POPULAR -> R.string.franchise_sort_popular
        ALPHABETICAL -> R.string.franchise_sort_az
        COLLECTIONS -> R.string.franchise_sort_collections
    }

    companion object {
        fun fromName(name: String?): FranchiseSort =
            entries.firstOrNull { it.name == name } ?: RANDOM
    }
}

/**
 * Which way a sort runs.
 *
 * Read relative to each sort's *natural* order, not to its raw key, because the keys do not all
 * point the same way:
 *
 * - Trending ranks from 1, so ascending rank means *more* trending first.
 * - Popularity is a like count, so descending means *more* popular first.
 * - A-Z reads forwards from A.
 *
 * [DESCENDING] is the natural order for every sort, which is why it is the default: it is the
 * order each option's own name promises — #1 trending, most liked, A to Z. [ASCENDING] inverts
 * that, giving the worst of each first. Storing a direction relative to the natural order keeps
 * one stored value meaningful across all four sorts; storing it against the raw key would make
 * "A-Z" show Z to A by default and "Trending" show its worst entry first.
 */
enum class FranchiseSortDirection {
    DESCENDING,
    ASCENDING,
    ;

    fun inverted() = if (this == DESCENDING) ASCENDING else DESCENDING

    /** What the direction snackbar shows, matching what the list is actually doing. */
    fun labelRes() = when (this) {
        DESCENDING -> R.string.franchise_sort_descending
        ASCENDING -> R.string.franchise_sort_ascending
    }

    companion object {
        fun fromName(name: String?): FranchiseSortDirection =
            entries.firstOrNull { it.name == name } ?: DESCENDING
    }
}

/**
 * How the Franchise row is ordered and what it shows.
 *
 * One value so the sort, the direction and the single-entry toggle cannot drift apart from each
 * other, and so the whole row can be rebuilt from preferences.
 */
data class FranchiseListPrefs(
    val sort: FranchiseSort = FranchiseSort.RANDOM,
    val direction: FranchiseSortDirection = FranchiseSortDirection.DESCENDING,
    /**
     * Whether a franchise holding a single entry is shown at all.
     *
     * Off by default: a one-poster card is a smaller version of the Popular row this replaced.
     */
    val showSingleEntry: Boolean = false,
) {
    companion object {
        val DEFAULT = FranchiseListPrefs()
    }
}

/**
 * Applies [FranchiseListPrefs] to a card list.
 *
 * Pure, so the ordering can be checked without a device and so [FranchiseSort.RANDOM] can
 * genuinely re-shuffle: the caller supplies the permutation, rather than the shuffle depending
 * on ambient RNG state that a repeated press might reproduce.
 *
 * ## Direction and random
 *
 * Random ignores [FranchiseSortDirection]. A shuffle has no meaningful ascending or descending
 * form, so inverting it cannot change anything about the order; it only re-rolls. The UI says
 * "Randomized" rather than claiming a direction that does not apply.
 */
object FranchiseSorter {

    /**
     * @param shuffledIn the permutation to use for [FranchiseSort.RANDOM], already shuffled by
     *   the caller so this stays deterministic and testable.
     */
    fun apply(
        franchises: List<Franchise>,
        prefs: FranchiseListPrefs,
        shuffledIn: List<Franchise> = franchises,
    ): List<Franchise> {
        val visible = if (prefs.showSingleEntry) franchises
        else franchises.filterNot { it.isSingleEntry }

        if (prefs.sort == FranchiseSort.RANDOM) {
            // With the switch on, every card is visible, so random is just the shuffle. With it
            // off there are no single-entry cards left to demote, but the branch is kept rather
            // than folded away: it is what makes the random order independent of the preference
            // for the multi-entry cards, should the filtering ever move upstream of here.
            val multiEntry = visible.filterNot { it.isSingleEntry }.mapTo(HashSet()) { it.name }
            return shuffledIn.sortedBy { if (it.name in multiEntry) 0 else 1 }
        }

        // Every branch below produces the sort's *natural* order. Direction is applied once, at
        // the end, so no individual sort can disagree with what its label promises.
        val natural = when (prefs.sort) {
            FranchiseSort.RANDOM -> visible // handled above
            // A card ranked #1 by the source is the most trending one, so a missing rank sorts
            // last rather than pretending to be #1.
            FranchiseSort.TRENDING -> visible.sortedBy { it.trendingRank ?: Int.MAX_VALUE }
            // Most liked first, and an unknown count sorts last rather than leading.
            FranchiseSort.POPULAR -> visible.sortedByDescending { it.likeCount ?: -1 }
            FranchiseSort.ALPHABETICAL -> visible.sortedBy { it.name.lowercase() }
            // Collections keep the source's own order: the sort *is* the source, so there is no
            // key to reorder by, and any reshuffle would only fight the curated order it exists
            // to preserve.
            FranchiseSort.COLLECTIONS -> visible
        }
        return if (prefs.direction == FranchiseSortDirection.DESCENDING) natural
        else natural.reversed()
    }
}

/**
 * The Franchise sort dialog, shared by both Explore pages.
 *
 * One implementation because both pages needed the same two controls and had already drifted
 * into two different versions of it, one of which set its view after showing the dialog and so
 * never displayed it at all.
 *
 * @param onChanged run after anything is saved, so the caller can re-sort its row.
 * @param showCollections whether the Collections option is offered. The anime row has no
 *   curated-list source, so showing the option there would let the user pick a sort that
 *   only empties the row; the movie row is the only caller that passes true.
 */
fun Fragment.showFranchiseSortDialog(
    showCollections: Boolean = false,
    onChanged: () -> Unit,
) {
    val prefs = FranchiseListPrefs.read()
    val body = layoutInflater.inflate(R.layout.dialog_franchise_sort, null)

    // Held rather than re-found: checkedRadioButtonId is a RadioGroup property, and the inflated
    // body is a plain View, so the group has to be kept around to read the answer back.
    val group = body.findViewById<RadioGroup>(R.id.franchiseSortGroup)
    body.findViewById<View>(R.id.franchiseSortCollections).isVisible = showCollections
    group.check(prefs.sort.radioId())
    body.findViewById<MaterialSwitch>(R.id.franchiseShowSingleEntry).apply {
        isSaveEnabled = false
        isChecked = prefs.showSingleEntry
        // Saved on change rather than on OK, so the row updates while the dialog is still open.
        // Worth the extra re-sort: the toggle can empty the row, and a dialog left showing a
        // state the user has already applied reads as stuck.
        setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.FranchiseShowSingleEntry, checked)
            onChanged()
        }
    }

    MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.franchise_sort_dialog_title)
        .setView(body)
        // Read on OK, so Cancel leaves the sort alone even though the toggle was saved already.
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val chosen = FranchiseSort.entries
                .firstOrNull { it.radioId() == group.checkedRadioButtonId } ?: prefs.sort
            PrefManager.setVal(PrefName.FranchiseSortOrder, chosen.name)
            onChanged()
        }
        .show()
}

/** Reads the row's sort, direction and toggle straight from preferences. */
fun FranchiseListPrefs.Companion.read() = FranchiseListPrefs(
    sort = FranchiseSort.fromName(PrefManager.getVal(PrefName.FranchiseSortOrder)),
    direction = FranchiseSortDirection.fromName(PrefManager.getVal(PrefName.FranchiseSortDirectionPref)),
    showSingleEntry = PrefManager.getVal(PrefName.FranchiseShowSingleEntry),
)

/**
 * This sort's radio button in [R.layout.dialog_franchise_sort].
 *
 * Resolved in code so the enum, the string labels and the dialog's views cannot disagree: an
 * option added to the enum with no button here would be unreachable.
 */
private fun FranchiseSort.radioId() = when (this) {
    FranchiseSort.RANDOM -> R.id.franchiseSortRandom
    FranchiseSort.TRENDING -> R.id.franchiseSortTrending
    FranchiseSort.POPULAR -> R.id.franchiseSortPopular
    FranchiseSort.ALPHABETICAL -> R.id.franchiseSortAlphabetical
    FranchiseSort.COLLECTIONS -> R.id.franchiseSortCollections
}
