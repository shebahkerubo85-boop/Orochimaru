package ani.sanin.settings

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.databinding.BottomSheetDirectUrlBinding
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.TvKeyboardUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrlPlayBottomSheet : DialogFragment() {

    private var _binding: BottomSheetDirectUrlBinding? = null
    private val binding get() = _binding!!

    /** When set, saves into a fixed Link slot (name = "Link N"). */
    var slotIndex: Int? = null
    var onSaved: (() -> Unit)? = null

    companion object {
        fun newInstance(slotIndex: Int? = null): UrlPlayBottomSheet =
            UrlPlayBottomSheet().apply { this.slotIndex = slotIndex }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDirectUrlBinding.inflate(inflater, container, false)
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
        dialog?.window?.let { TvKeyboardUtil.retainWindowFocus(it) }

        binding.directUrlTitle.text = if (slotIndex != null) {
            getString(R.string.configure) + " " + DirectUrlManager.slotName(slotIndex!!)
        } else {
            getString(R.string.add_direct_url)
        }

        TvKeyboardUtil.setupTvInput(binding.directUrlInput)

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                if (TvKeyboardUtil.isCompactKeyboardVisible(binding.directUrlInput)) {
                    binding.directUrlInput.clearFocus()
                    binding.directUrlSave.requestFocus()
                    TvKeyboardUtil.hideCompactKeyboard(binding.directUrlInput)
                    return@setOnKeyListener true
                }
            }
            false
        }

        binding.directUrlCancel.setOnClickListener { dismiss() }
        binding.directUrlSave.setOnClickListener { save() }
        FocusEffectUtil.applyFocusListener(binding.directUrlSave, binding.directUrlCancel)
    }

    private fun save() {
        val input = binding.directUrlInput.text?.toString()?.trim().orEmpty()
        if (!DirectUrlManager.isValidUrl(input)) {
            binding.directUrlError.visibility = View.VISIBLE
            binding.directUrlError.text = getString(R.string.direct_url_error_invalid)
            return
        }
        binding.directUrlError.visibility = View.GONE
        val savingLabel = getString(R.string.direct_url_saving)
        binding.directUrlSave.isEnabled = false
        binding.directUrlTitle.text = savingLabel
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                UrlVideoExtractor.extract(input)
            }
            if (!isAdded) return@launch
            binding.directUrlSave.isEnabled = true
            binding.directUrlTitle.text = if (slotIndex != null) {
                getString(R.string.configure) + " " + DirectUrlManager.slotName(slotIndex!!)
            } else {
                getString(R.string.add_direct_url)
            }
            if (result.videos.isEmpty()) {
                binding.directUrlError.visibility = View.VISIBLE
                binding.directUrlError.text = getString(R.string.direct_url_error_no_video)
                return@launch
            }
            val ctx = requireContext()
            val name = if (slotIndex != null) {
                DirectUrlManager.slotName(slotIndex!!)
            } else {
                DirectUrlManager.extractSiteName(input)
            }
            DirectUrlManager.saveConfig(ctx, DirectUrlManager.DirectUrlConfig(name, input, true, slotIndex))
            Logger.log("DIRECT_URL: saved '$name' <- $input (videos=${result.videos.size})")
            toast(getString(R.string.direct_url_success))
            onSaved?.invoke()
            dismiss()
        }
    }

}
