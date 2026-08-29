package ani.sanin.others.webview

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.R
import ani.sanin.cloudstream.TmdbPlayerActivity
import ani.sanin.databinding.BottomSheetUrlPlayBinding
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrlPlayBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetUrlPlayBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetUrlPlayBinding.inflate(inflater, container, false)
        FocusEffectUtil.applyFocusListener(binding.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.apply {
            setGravity(Gravity.TOP)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(R.drawable.top_sheet_background)
        }

        TvKeyboardUtil.setupTvInput(binding.urlInput)
        dialog?.window?.let { TvKeyboardUtil.retainWindowFocus(it) }

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (TvKeyboardUtil.isCompactKeyboardVisible(binding.urlInput)) {
                    binding.urlInput.clearFocus()
                    binding.playButton.requestFocus()
                    TvKeyboardUtil.hideCompactKeyboard(binding.urlInput)
                    return@setOnKeyListener true
                }
            }
            false
        }

        binding.playButton.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isEmpty()) {
                binding.urlError.visibility = View.VISIBLE
                binding.urlError.text = "Enter a URL"
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                binding.urlError.visibility = View.VISIBLE
                binding.urlError.text = "URL must start with http:// or https://"
                return@setOnClickListener
            }
            binding.urlError.visibility = View.GONE
            startExtraction(url)
        }

        binding.urlInput.setOnEditorActionListener { textView, action, keyEvent ->
            if (action == EditorInfo.IME_ACTION_DONE ||
                (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                binding.playButton.performClick()
                return@setOnEditorActionListener true
            }
            false
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }
    }

    private fun startExtraction(url: String) {
        binding.playButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "Extracting video..."
        binding.urlInput.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            val result = UrlVideoExtractor.extract(url)
            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                binding.progressBar.visibility = View.GONE
                binding.urlInput.isEnabled = true

                result.onSuccess { videos ->
                    if (videos.isEmpty()) {
                        showExtractionError("No video found on this page")
                        return@onSuccess
                    }

                    // Pick the best video (prefer m3u8 > mp4 > mpd > webm)
                    val bestVideo = videos.sortedBy { vid ->
                        when {
                            vid.url.contains(".m3u8") -> 0
                            vid.url.contains(".mp4") -> 1
                            vid.url.contains(".mpd") -> 2
                            vid.url.contains(".webm") -> 3
                            else -> 4
                        }
                    }.first()

                    // Launch the player
                    val intent = Intent(requireContext(), TmdbPlayerActivity::class.java).apply {
                        putExtra(TmdbPlayerActivity.EXTRA_URL, bestVideo.url)
                        putExtra(TmdbPlayerActivity.EXTRA_TITLE, bestVideo.title ?: url)
                    }
                    startActivity(intent)
                    dismiss()
                }.onFailure { error ->
                    showExtractionError(error.message ?: "Extraction failed")
                }
            }
        }
    }

    private fun showExtractionError(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
        binding.statusText.setTextColor(0xFFF44336.toInt())
        binding.playButton.isEnabled = true
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            val sheet = UrlPlayBottomSheet()
            sheet.show(fragmentManager, "url_play")
        }
    }
}
