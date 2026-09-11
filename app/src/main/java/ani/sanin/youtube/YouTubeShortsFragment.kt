package ani.sanin.youtube

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.sanin.databinding.FragmentYoutubeShortsBinding
import ani.sanin.util.Logger
import kotlinx.coroutines.launch

class YouTubeShortsFragment : Fragment() {

    private var _binding: FragmentYoutubeShortsBinding? = null
    private val binding get() = _binding!!
    private val adapter = YouTubeShortsAdapter { short ->
        val url = "https://www.youtube.com/watch?v=${short.id}"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentYoutubeShortsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.d("YouTubeShorts", "Fragment created, setting up RecyclerView")
        binding.shortsRecyclerView.adapter = adapter
        val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 2 else 4
        binding.shortsRecyclerView.layoutManager = GridLayoutManager(requireContext(), spanCount)
        loadShorts()
    }

    private fun loadShorts() {
        Logger.d("YouTubeShorts", "loadShorts called")
        binding.shortsProgressBar.visibility = View.VISIBLE
        binding.shortsRecyclerView.visibility = View.GONE
        binding.shortsErrorText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Logger.d("YouTubeShorts", "Fetching shorts from API...")
                val shorts = YouTubeApi.fetchShorts(50)
                Logger.d("YouTubeShorts", "Fetched ${shorts.size} shorts")
                binding.shortsProgressBar.visibility = View.GONE
                if (shorts.isEmpty()) {
                    binding.shortsErrorText.visibility = View.VISIBLE
                    binding.shortsErrorText.text = "No shorts found"
                } else {
                    binding.shortsRecyclerView.visibility = View.VISIBLE
                    adapter.submitList(shorts)
                }
            } catch (e: Exception) {
                Logger.d("YouTubeShorts", "Error: ${e.message}")
                e.printStackTrace()
                binding.shortsProgressBar.visibility = View.GONE
                binding.shortsErrorText.visibility = View.VISIBLE
                binding.shortsErrorText.text = "Failed to load: ${e.message}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
