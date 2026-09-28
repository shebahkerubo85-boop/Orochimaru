package ani.sanin.media.user

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import ani.sanin.databinding.FragmentListBinding
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.OtherDetailsViewModel
import ani.sanin.cloudstream.TmdbCards

class ListFragment : Fragment() {
    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private var pos: Int? = null
    private var calendar = false
    private var grid: Boolean? = null
    private var list: MutableList<Media>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pos = it.getInt("list")
            calendar = it.getBoolean("calendar")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val screenWidth = resources.displayMetrics.run { widthPixels / density }

        fun update() {
            if (grid != null && list != null) {
                val adapter = MediaAdaptor(if (grid!!) 0 else 1, list!!, requireActivity(), true)
                binding.listRecyclerView.layoutManager =
                    GridLayoutManager(
                        requireContext(),
                        if (grid!!) TmdbCards.gridSpan(screenWidth) else 1
                    )
                binding.listRecyclerView.adapter = adapter
            }
        }

        if (calendar) {
            val model: OtherDetailsViewModel by activityViewModels()
            model.getCalendar().observe(viewLifecycleOwner) {
                if (it != null) {
                    list = it.values.toList().getOrNull(pos!!)
                    update()
                }
            }
            grid = true
        } else {
            val model: ListViewModel by activityViewModels()
            model.getLists().observe(viewLifecycleOwner) {
                if (it != null) {
                    list = it.values.toList().getOrNull(pos!!)
                    update()
                }
            }
            model.grid.observe(viewLifecycleOwner) {
                grid = it
                update()
            }
        }
    }

    fun randomOptionClick() {
        val adapter = binding.listRecyclerView.adapter as MediaAdaptor
        adapter.randomOptionClick()
    }

    companion object {
        fun newInstance(pos: Int, calendar: Boolean = false): ListFragment =
            ListFragment().apply {
                arguments = Bundle().apply {
                    putInt("list", pos)
                    putBoolean("calendar", calendar)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles configChanges, so this fragment's view survives the
        // rotation and the span built in onViewCreated is still the portrait one. This
        // list can also be showing in list mode, which is a fixed span of 1 and must be
        // left alone.
        if (grid == true) {
            _binding?.listRecyclerView?.let { TmdbCards.applyGridSpan(it) }
        }
    }
}