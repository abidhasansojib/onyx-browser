package com.onyx.browser.ui.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.onyx.browser.R
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.web.OnyxWebView

/**
 * Encapsulates the Find-in-Page search bar lifecycle, query input,
 * regex mode toggling, next/previous navigation, and match count indicators.
 */
class FindInPageController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val getActiveWebView: () -> OnyxWebView?
) {

    var isRegexFindEnabled: Boolean = false
        private set

    val isVisible: Boolean
        get() = binding.findInPageBar.visibility == View.VISIBLE

    fun setup() {
        binding.btnCloseFind.setOnClickListener {
            hide()
        }

        binding.btnRegexToggle.setOnClickListener {
            isRegexFindEnabled = !isRegexFindEnabled
            val activeColor = ContextCompat.getColor(activity, R.color.primary)
            binding.btnRegexToggle.setTextColor(if (isRegexFindEnabled) activeColor else Color.GRAY)
            val webView = getActiveWebView()
            webView?.regexFindBridge?.setRegexMode(isRegexFindEnabled)
            val query = binding.etFindQuery.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                webView?.regexFindBridge?.find(query)
            }
        }

        binding.etFindQuery.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            val webView = getActiveWebView()
            if (query.isNotEmpty()) {
                webView?.regexFindBridge?.find(query)
            } else {
                webView?.regexFindBridge?.clearMatches()
                binding.tvFindMatches.text = activity.getString(R.string.no_matches)
            }
        }

        binding.etFindQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                getActiveWebView()?.regexFindBridge?.findNext(true)
                true
            } else {
                false
            }
        }

        binding.btnFindPrev.setOnClickListener {
            getActiveWebView()?.regexFindBridge?.findNext(false)
        }

        binding.btnFindNext.setOnClickListener {
            getActiveWebView()?.regexFindBridge?.findNext(true)
        }
    }

    fun show() {
        val webView = getActiveWebView() ?: return
        binding.findInPageBar.visibility = View.VISIBLE
        binding.tvFindMatches.text = activity.getString(R.string.no_matches)

        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            if (numberOfMatches > 0) {
                binding.tvFindMatches.text = activity.getString(R.string.matches_count, activeMatchOrdinal + 1, numberOfMatches)
            } else {
                binding.tvFindMatches.text = activity.getString(R.string.no_matches)
            }
        }

        binding.etFindQuery.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etFindQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        binding.findInPageBar.visibility = View.GONE
        getActiveWebView()?.regexFindBridge?.clearMatches()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etFindQuery.windowToken, 0)
        binding.etFindQuery.setText("")
    }
}
