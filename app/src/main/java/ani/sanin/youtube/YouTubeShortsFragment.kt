package ani.sanin.youtube

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.sanin.databinding.FragmentYoutubeShortsBinding
import android.util.Log
import kotlinx.coroutines.launch

class YouTubeShortsFragment : Fragment() {

    private var _binding: FragmentYoutubeShortsBinding? = null
    private val binding get() = _binding!!
    private var shortAdapter: YouTubeShortsAdapter? = null
    private var lastSelectedId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentYoutubeShortsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 2 else 4
        shortAdapter = YouTubeShortsAdapter { short ->
            lastSelectedId = short.id
            openPlayer()
        }
        binding.shortsRecyclerView.adapter = shortAdapter
        binding.shortsRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
        loadShorts()
    }

    private fun openPlayer() {
        val shorts = (shortAdapter?.currentList).orEmpty()
        if (shorts.isEmpty()) return
        val startIndex = shorts.indexOfFirst { it.id == lastSelectedId }.coerceAtLeast(0)
        val intent = android.content.Intent(requireContext(), YouTubeShortsPlayerActivity::class.java).apply {
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_VIDEO_IDS,
                java.util.ArrayList(shorts.map { it.id })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_TITLES,
                java.util.ArrayList(shorts.map { it.title })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_CHANNELS,
                java.util.ArrayList(shorts.map { "Aniphex" })
            )
            putExtra(YouTubeShortsPlayerActivity.EXTRA_START_INDEX, startIndex)
        }
        startActivity(intent)
    }

    private fun loadShorts() {
        Log.d("YouTubeShorts", "loadShorts called")
        val b = _binding ?: return
        b.shortsProgressBar.visibility = View.VISIBLE
        b.shortsRecyclerView.visibility = View.GONE
        b.shortsErrorText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("YouTubeShorts", "Fetching shorts from API...")
                val shorts = YouTubeApi.fetchShorts(50)
                Log.d("YouTubeShorts", "Fetched ${shorts.size} shorts")
                val bind = _binding ?: return@launch
                bind.shortsProgressBar.visibility = View.GONE
                if (shorts.isEmpty()) {
                    bind.shortsErrorText.visibility = View.VISIBLE
                    bind.shortsErrorText.text = "No shorts found"
                } else {
                    bind.shortsRecyclerView.visibility = View.VISIBLE
                    shortAdapter?.submitList(shorts)
                }
            } catch (e: Exception) {
                Log.d("YouTubeShorts", "Error: ${e.message}")
                val bind = _binding ?: return@launch
                bind.shortsProgressBar.visibility = View.GONE
                bind.shortsErrorText.visibility = View.VISIBLE
                bind.shortsErrorText.text = "Failed to load: ${e.message}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shortAdapter = null
        _binding = null
    }
}
