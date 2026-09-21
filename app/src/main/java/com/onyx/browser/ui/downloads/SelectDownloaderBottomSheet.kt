package com.onyx.browser.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetSelectDownloaderBinding

class SelectDownloaderBottomSheet(
    private val downloaders: List<ExternalDownloaderInfo>,
    private val onDownloaderSelected: (ExternalDownloaderInfo) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSelectDownloaderBinding? = null
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
        _binding = BottomSheetSelectDownloaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        val adapter = DownloaderPickerAdapter { selected ->
            onDownloaderSelected(selected)
            dismiss()
        }

        binding.rvDownloaders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloaders.adapter = adapter
        adapter.submitList(downloaders)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SelectDownloaderBottomSheet"
    }
}
