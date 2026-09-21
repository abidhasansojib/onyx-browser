package com.onyx.browser.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetFilterListsBinding

class FilterListsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetFilterListsBinding? = null
    private val binding get() = _binding!!

    companion object {
        val FILTER_LISTS = linkedMapOf(
            "easylist" to "EasyList",
            "easyprivacy" to "EasyPrivacy",
            "ublock" to "uBlock Filters",
            "brave_default" to "Brave Default Filters",
            "fanboy_annoyance" to "Fanboy Annoyance",
            "adguard_base" to "AdGuard Base Filter",
            "adguard_mobile" to "AdGuard Mobile Ads",
            "peter_lowes" to "Peter Lowe's List",
            "brave_social" to "Brave Social Media",
            "cookie_notices" to "Cookie Consent Notices"
        )

        fun newInstance() = FilterListsBottomSheet()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetFilterListsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = BrowserPreferences.getInstance(requireContext())

        val items = FILTER_LISTS.map { (key, name) ->
            FilterListItem(key, name, prefs.isFilterListEnabled(key))
        }

        val adapter = FilterListAdapter(items) { key, enabled ->
            prefs.setFilterListEnabled(key, enabled)
        }
        binding.rvFilterLists.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class FilterListItem(val key: String, val name: String, var enabled: Boolean)

    inner class FilterListAdapter(
        private val items: List<FilterListItem>,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<FilterListAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tvFilterListName)
            val cb: CheckBox = itemView.findViewById(R.id.cbFilterList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_filter_list, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.cb.isChecked = item.enabled
            holder.itemView.setOnClickListener {
                item.enabled = !item.enabled
                holder.cb.isChecked = item.enabled
                onToggle(item.key, item.enabled)
            }
        }

        override fun getItemCount() = items.size
    }
}
