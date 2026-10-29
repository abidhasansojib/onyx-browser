package com.onyx.browser.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetInstallUserscriptBinding
import com.onyx.browser.userscript.UserScriptManager
import com.onyx.browser.userscript.UserScriptMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class InstallUserScriptBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetInstallUserscriptBinding? = null
    private val binding get() = _binding!!

    private var scriptUrl: String = ""
    private var downloadedCode: String = ""
    private var parsedMetadata: UserScriptMetadata? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptUrl = arguments?.getString(ARG_SCRIPT_URL).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetInstallUserscriptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnErrorDismiss.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        if (scriptUrl.isBlank()) {
            showError("Invalid userscript URL.")
            return
        }

        fetchAndParseScript()
    }

    private fun fetchAndParseScript() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutContent.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        val domain = try { Uri.parse(scriptUrl).host ?: scriptUrl } catch (_: Exception) { scriptUrl }
        binding.tvDialogSubtitle.text = getString(R.string.userscripts_fetching) + " ($domain)"

        lifecycleScope.launch(Dispatchers.IO) {
            val responseBody = try {
                val req = Request.Builder()
                    .url(scriptUrl)
                    .header("User-Agent", "OnyxBrowser/1.0 UserScriptEngine")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            showError("HTTP ${resp.code}: Failed to download script from server.")
                        }
                        return@launch
                    }
                    resp.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(e.message ?: "Network error downloading script.")
                }
                return@launch
            }

            if (responseBody.isBlank()) {
                withContext(Dispatchers.Main) {
                    showError("Empty script downloaded.")
                }
                return@launch
            }

            downloadedCode = responseBody
            val metadata = try {
                UserScriptMetadata.parse(responseBody)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Failed to parse metadata: ${e.message}")
                }
                return@launch
            }

            parsedMetadata = metadata

            withContext(Dispatchers.Main) {
                displayMetadata(metadata, domain)
            }
        }
    }

    private fun displayMetadata(meta: UserScriptMetadata, domain: String) {
        if (_binding == null) return

        binding.layoutLoading.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.layoutContent.visibility = View.VISIBLE

        binding.tvDialogSubtitle.text = "Review permissions before installing"

        // Title & Author
        binding.tvScriptName.text = meta.name.ifBlank { "Unnamed Script" }
        val authorInfo = if (meta.author.isNotBlank()) "by ${meta.author}" else "Local script"
        val versionInfo = if (meta.version.isNotBlank()) "v${meta.version}" else ""
        binding.tvScriptVersionAuthor.text = listOf(versionInfo, authorInfo).filter { it.isNotBlank() }.joinToString(" • ")
        binding.tvScriptSourceDomain.text = "Source: $domain"

        // Description
        if (meta.description.isNotBlank()) {
            binding.cardDescription.visibility = View.VISIBLE
            binding.tvScriptDescription.text = meta.description
        } else {
            binding.cardDescription.visibility = View.GONE
        }

        // Matching target sites
        val matchRules = when {
            meta.matches.isNotEmpty() -> meta.matches
            meta.includes.isNotEmpty() -> meta.includes
            else -> emptyList()
        }
        binding.tvScriptMatches.text = if (matchRules.isNotEmpty()) {
            matchRules.joinToString("\n")
        } else {
            getString(R.string.userscripts_all_sites)
        }

        // Permissions & Grants
        val grants = meta.grants
        if (grants.isNotEmpty()) {
            binding.tvScriptGrants.text = grants.joinToString(", ")
        } else {
            binding.tvScriptGrants.text = getString(R.string.userscripts_perm_none)
        }

        // Cross-domain request warning
        val hasNetworkAccess = grants.any { it.contains("xmlhttpRequest", ignoreCase = true) }
        binding.layoutWarningNetwork.visibility = if (hasNetworkAccess) View.VISIBLE else View.GONE

        // View Source Button: Open script in full-screen editor
        binding.btnViewSource.setOnClickListener {
            UserScriptEditorActivity.pendingCodeToOpen = downloadedCode
            val intent = Intent(requireContext(), UserScriptEditorActivity::class.java).apply {
                putExtra(UserScriptEditorActivity.EXTRA_SOURCE_URL, scriptUrl)
            }
            startActivity(intent)
            dismiss()
        }

        // Install Button
        binding.btnInstall.setOnClickListener {
            installScript(meta)
        }
    }

    private fun installScript(meta: UserScriptMetadata) {
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = UserScriptManager.getInstance(requireContext())
            manager.addScript(downloadedCode, scriptUrl)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.userscripts_installed_success, meta.name),
                    Toast.LENGTH_LONG
                ).show()
                dismiss()
            }
        }
    }

    private fun showError(message: String) {
        if (_binding == null) return
        binding.layoutLoading.visibility = View.GONE
        binding.layoutContent.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "InstallUserScriptBottomSheet"
        private const val ARG_SCRIPT_URL = "arg_script_url"

        fun show(fragmentManager: FragmentManager, scriptUrl: String) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val sheet = InstallUserScriptBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCRIPT_URL, scriptUrl)
                }
            }
            sheet.show(fragmentManager, TAG)
        }
    }
}
