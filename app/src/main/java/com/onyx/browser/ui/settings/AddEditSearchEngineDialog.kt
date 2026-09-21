package com.onyx.browser.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.databinding.BottomSheetAddSearchEngineBinding
import java.util.UUID

class AddEditSearchEngineDialog(
    private val existingEngine: SearchEngine? = null,
    private val onSave: (SearchEngine) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddSearchEngineBinding? = null
    private val binding get() = _binding!!

    // Suggested engines to quick-fill the form
    private val suggestions = listOf(
        Triple("Ecosia", "eco", "https://www.ecosia.org/search?q=%s"),
        Triple("Kagi", "k", "https://kagi.com/search?q=%s"),
        Triple("Perplexity", "px", "https://www.perplexity.ai/search?q=%s"),
        Triple("Startpage", "sp", "https://www.startpage.com/do/dsearch?query=%s"),
        Triple("Searx", "sx", "https://searx.be/search?q=%s"),
        Triple("Qwant", "qw", "https://www.qwant.com/?q=%s"),
        Triple("Mojeek", "mj", "https://www.mojeek.com/search?q=%s"),
        Triple("Whoogle", "wg", "https://whoogle.io/search?q=%s")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddSearchEngineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isEdit = existingEngine != null

        // Set title and button label
        if (isEdit) {
            binding.tvDialogTitle.text = "Edit search engine"
            binding.btnSave.text = getString(R.string.save)
            // Prefill existing values
            binding.etEngineName.setText(existingEngine?.displayName)
            binding.etEngineKeyword.setText(existingEngine?.keyword)
            binding.etEngineUrl.setText(existingEngine?.queryUrl)
            // Hide suggestions row when editing
            binding.llQuickSuggestions.visibility = View.GONE
            binding.llQuickSuggestions.parent?.let { parent ->
                if (parent is ViewGroup) {
                    // Also hide the label above
                }
            }
        } else {
            binding.tvDialogTitle.text = "Add search engine"
            binding.btnSave.text = getString(R.string.add)
            buildSuggestionChips()
        }

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnSave.setOnClickListener {
            if (validateAndSave()) dismiss()
        }
    }

    private fun buildSuggestionChips() {
        val inflater = LayoutInflater.from(requireContext())
        suggestions.forEach { (name, keyword, url) ->
            val chip = inflater.inflate(R.layout.item_suggestion_chip, binding.llQuickSuggestions, false)
            chip.findViewById<TextView>(R.id.tvChipLabel)?.text = name
            chip.setOnClickListener {
                binding.etEngineName.setText(name)
                binding.etEngineKeyword.setText(keyword)
                binding.etEngineUrl.setText(url)
                // Clear errors
                binding.tilEngineName.error = null
                binding.tilEngineUrl.error = null
            }
            binding.llQuickSuggestions.addView(chip)
        }
    }

    private fun validateAndSave(): Boolean {
        val name = binding.etEngineName.text?.toString()?.trim() ?: ""
        val keyword = binding.etEngineKeyword.text?.toString()?.trim() ?: ""
        var url = binding.etEngineUrl.text?.toString()?.trim() ?: ""

        // Validate name
        if (name.isBlank()) {
            binding.tilEngineName.error = "Name cannot be empty"
            binding.etEngineName.requestFocus()
            return false
        }
        binding.tilEngineName.error = null

        // Validate URL
        if (url.isBlank()) {
            binding.tilEngineUrl.error = "URL cannot be empty"
            binding.etEngineUrl.requestFocus()
            return false
        }
        binding.tilEngineUrl.error = null

        // Auto-prepend https if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        // Auto-append %s if not present
        if (!url.contains("%s")) {
            url = if (url.contains("?")) "$url&q=%s" else "$url?q=%s"
        }

        val homeUrl = try {
            val uri = android.net.Uri.parse(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { "" }

        val engine = SearchEngine(
            id = existingEngine?.id
                ?: "custom_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            displayName = name,
            queryUrl = url,
            homeUrl = homeUrl,
            iconResId = R.drawable.ic_web,
            keyword = keyword,
            isCustom = true
        )

        // Dismiss keyboard
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        } catch (_: Exception) {}

        onSave(engine)
        Toast.makeText(
            requireContext(),
            if (existingEngine != null) "Updated ${engine.displayName}" else "Added ${engine.displayName}",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddEditSearchEngineDialog"
    }
}
