package com.onyx.browser.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.databinding.BottomSheetSearchEnginePickerBinding
import com.onyx.browser.databinding.ItemSearchEngineBinding

class SearchEnginePickerDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSearchEnginePickerBinding? = null
    private val binding get() = _binding!!

    var onSearchEngineSelected: ((SearchEngine) -> Unit)? = null
    private var currentEngine: SearchEngine = SearchEngine.BRAVE

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = BottomSheetSearchEnginePickerBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val engines = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(requireContext()).getAllSearchEngines()
        binding.rvSearchEngines.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchEngines.adapter = EngineAdapter(engines) { selected ->
            onSearchEngineSelected?.invoke(selected)
            dismiss()
        }
        binding.btnCancelSearchEngine.setOnClickListener { dismiss() }
    }

    fun setSelectedEngine(engine: SearchEngine) { currentEngine = engine }

    private inner class EngineAdapter(
        private val list: List<SearchEngine>,
        private val onSelect: (SearchEngine) -> Unit
    ) : RecyclerView.Adapter<EngineAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemSearchEngineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }
        override fun onBindViewHolder(h: ViewHolder, pos: Int) = h.bind(list[pos])
        override fun getItemCount() = list.size
        inner class ViewHolder(private val b: ItemSearchEngineBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: SearchEngine) {
                b.tvEngineName.text = item.displayName
                b.ivEngineIcon.setImageResource(item.iconResId)
                b.rbSelected.isChecked = (item.id == currentEngine.id)
                b.root.setOnClickListener { onSelect(item) }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    companion object { const val TAG = "SearchEnginePickerDialog" }
}
