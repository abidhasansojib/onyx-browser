package com.onyx.browser.ui.menu

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.DialogSelectLanguageBinding
import com.onyx.browser.databinding.ItemLanguageOptionBinding

data class TranslateLanguage(
    val code: String,
    val name: String
)

class LanguageSelectionDialog(
    private val onLanguageSelected: (code: String, name: String) -> Unit
) : DialogFragment() {

    private var _binding: DialogSelectLanguageBinding? = null
    private val binding get() = _binding!!

    private val supportedLanguages = listOf(
        TranslateLanguage("en", "English"),
        TranslateLanguage("es", "Spanish (Español)"),
        TranslateLanguage("fr", "French (Français)"),
        TranslateLanguage("de", "German (Deutsch)"),
        TranslateLanguage("zh-CN", "Chinese Simplified (简体中文)"),
        TranslateLanguage("zh-TW", "Chinese Traditional (繁體中文)"),
        TranslateLanguage("ja", "Japanese (日本語)"),
        TranslateLanguage("ko", "Korean (한국어)"),
        TranslateLanguage("ru", "Russian (Русский)"),
        TranslateLanguage("ar", "Arabic (العربية)"),
        TranslateLanguage("pt", "Portuguese (Português)"),
        TranslateLanguage("pt-BR", "Portuguese Brazil (Português Brasil)"),
        TranslateLanguage("it", "Italian (Italiano)"),
        TranslateLanguage("hi", "Hindi (हिन्दी)"),
        TranslateLanguage("bn", "Bengali (বাংলা)"),
        TranslateLanguage("tr", "Turkish (Türkçe)"),
        TranslateLanguage("id", "Indonesian (Bahasa Indonesia)"),
        TranslateLanguage("vi", "Vietnamese (Tiếng Việt)"),
        TranslateLanguage("pl", "Polish (Polski)"),
        TranslateLanguage("nl", "Dutch (Nederlands)"),
        TranslateLanguage("th", "Thai (ไทย)"),
        TranslateLanguage("sv", "Swedish (Svenska)"),
        TranslateLanguage("da", "Danish (Dansk)"),
        TranslateLanguage("no", "Norwegian (Norsk)"),
        TranslateLanguage("fi", "Finnish (Suomi)"),
        TranslateLanguage("cs", "Czech (Čeština)"),
        TranslateLanguage("sk", "Slovak (Slovenčina)"),
        TranslateLanguage("hu", "Hungarian (Magyar)"),
        TranslateLanguage("ro", "Romanian (Română)"),
        TranslateLanguage("uk", "Ukrainian (Українська)"),
        TranslateLanguage("el", "Greek (Ελληνικά)"),
        TranslateLanguage("he", "Hebrew (עברית)"),
        TranslateLanguage("fa", "Persian (فارسی)"),
        TranslateLanguage("ur", "Urdu (اردو)"),
        TranslateLanguage("ms", "Malay (Bahasa Melayu)"),
        TranslateLanguage("fil", "Filipino (Filipino)"),
        TranslateLanguage("bg", "Bulgarian (Български)"),
        TranslateLanguage("hr", "Croatian (Hrvatski)"),
        TranslateLanguage("sr", "Serbian (Српски)"),
        TranslateLanguage("ca", "Catalan (Català)"),
        TranslateLanguage("lt", "Lithuanian (Lietuvių)"),
        TranslateLanguage("lv", "Latvian (Latviešu)"),
        TranslateLanguage("et", "Estonian (Eesti)"),
        TranslateLanguage("af", "Afrikaans"),
        TranslateLanguage("sw", "Swahili (Kiswahili)")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        _binding = DialogSelectLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = BrowserPreferences.getInstance(requireContext())
        var currentCode = prefs.targetTranslateLanguage

        val adapter = LanguageAdapter(supportedLanguages, currentCode) { selected ->
            prefs.targetTranslateLanguage = selected.code
            prefs.targetTranslateLanguageName = selected.name.substringBefore(" (")
            onLanguageSelected(selected.code, prefs.targetTranslateLanguageName)
            dismiss()
        }

        binding.rvLanguages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLanguages.adapter = adapter

        binding.btnCancelLanguage.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxAllowedWidth = (420 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(maxAllowedWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class LanguageAdapter(
        private val items: List<TranslateLanguage>,
        private val selectedCode: String,
        private val onItemClick: (TranslateLanguage) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemLanguageOptionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLanguageOptionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvLanguageName.text = item.name
            holder.binding.ivLanguageSelected.visibility =
                if (item.code.equals(selectedCode, ignoreCase = true)) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val TAG = "LanguageSelectionDialog"
    }
}
