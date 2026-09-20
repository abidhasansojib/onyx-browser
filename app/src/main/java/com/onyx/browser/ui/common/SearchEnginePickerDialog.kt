package com.onyx.browser.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.databinding.DialogSearchEnginePickerBinding
import com.onyx.browser.databinding.ItemSearchEngineBinding

class SearchEnginePickerDialog : DialogFragment() {

    private var _binding: DialogSearchEnginePickerBinding? = null
    private val binding get() = _binding!!

    var onSearchEngineSelected: ((SearchEngine) -> Unit)? = null
    private var currentEngine: SearchEngine = SearchEngine.BRAVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchEnginePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSearchEngines.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchEngines.adapter = EngineAdapter(SearchEngine.entries) { selected ->
            onSearchEngineSelected?.invoke(selected)
            dismiss()
        }

        binding.btnCancelSearchEngine.setOnClickListener { dismiss() }
    }

    fun setSelectedEngine(engine: SearchEngine) {
        currentEngine = engine
    }

    private inner class EngineAdapter(
        private val list: List<SearchEngine>,
        private val onSelect: (SearchEngine) -> Unit
    ) : RecyclerView.Adapter<EngineAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemSearchEngineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount(): Int = list.size

        inner class ViewHolder(private val itemBinding: ItemSearchEngineBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(item: SearchEngine) {
                itemBinding.tvEngineName.text = item.displayName
                itemBinding.ivEngineIcon.setImageResource(item.iconResId)
                itemBinding.rbSelected.isChecked = (item == currentEngine)

                itemBinding.root.setOnClickListener {
                    onSelect(item)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SearchEnginePickerDialog"
    }
}
