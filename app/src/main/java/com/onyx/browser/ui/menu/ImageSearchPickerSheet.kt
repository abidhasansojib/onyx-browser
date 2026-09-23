package com.onyx.browser.ui.menu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetImageSearchBinding

/**
 * Bottom sheet that lets the user pick a reverse image search engine.
 * Each engine constructs its own query URL from the supplied [imageUrl].
 */
class ImageSearchPickerSheet(
    private val imageUrl: String,
    private val onEngineSelected: (searchUrl: String, engineName: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetImageSearchBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val encoded = java.net.URLEncoder.encode(imageUrl, "UTF-8")

        binding.optionGoogleLens.setOnClickListener {
            // Google Lens — opens the URL-based Lens search
            val url = "https://lens.google.com/uploadbyurl?url=$encoded"
            onEngineSelected(url, "Google Lens")
            dismiss()
        }

        binding.optionTinEye.setOnClickListener {
            // Trailing slash is required for TinEye's URL parameter to be accepted
            val url = "https://tineye.com/search/?url=$encoded"
            onEngineSelected(url, "TinEye")
            dismiss()
        }

        binding.optionYandex.setOnClickListener {
            val url = "https://yandex.com/images/search?rpt=imageview&url=$encoded"
            onEngineSelected(url, "Yandex Images")
            dismiss()
        }

        binding.optionBing.setOnClickListener {
            // FORM=SBIIDP is required for Bing Visual Search to process the image URL correctly
            val url = "https://www.bing.com/images/search?view=detailv2&iss=sbi&FORM=SBIIDP&q=imgurl:$encoded"
            onEngineSelected(url, "Bing Visual Search")
            dismiss()
        }

        binding.optionSauceNao.setOnClickListener {
            val url = "https://saucenao.com/search.php?url=$encoded"
            onEngineSelected(url, "SauceNAO")
            dismiss()
        }

        binding.optionAscii2d.setOnClickListener {
            val url = "https://ascii2d.net/search/url/$encoded"
            onEngineSelected(url, "ASCII2D")
            dismiss()
        }

        binding.btnDismissImageSearch.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImageSearchPickerSheet"
    }
}
