package com.onyx.browser.ui.dialog

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
import com.onyx.browser.R
import com.onyx.browser.databinding.DialogOpenInAppPromptBinding

class OpenInAppPromptDialog : DialogFragment() {

    private var _binding: DialogOpenInAppPromptBinding? = null
    private val binding get() = _binding

    var targetIntent: Intent? = null
    var appName: String? = null
    var onStayInOnyx: (() -> Unit)? = null
    var onOpenInApp: (() -> Unit)? = null

    private var actionTaken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        _binding = DialogOpenInAppPromptBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = _binding ?: return
        val ctx = context ?: return

        val intent = targetIntent
        val pm = ctx.packageManager
        var resolvedIcon: Drawable? = null
        var resolvedName = appName

        if (intent != null) {
            try {
                val resolveInfo = pm.resolveActivity(intent, 0)
                if (resolveInfo != null) {
                    resolvedIcon = resolveInfo.loadIcon(pm)
                    if (resolvedName.isNullOrBlank()) {
                        val label = resolveInfo.loadLabel(pm).toString()
                        if (label.isNotBlank()) resolvedName = label
                    }
                }
            } catch (_: Exception) {}

            if (resolvedIcon == null) {
                val pkg = intent.`package`
                if (!pkg.isNullOrBlank()) {
                    try {
                        resolvedIcon = pm.getApplicationIcon(pkg)
                        if (resolvedName.isNullOrBlank()) {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val label = pm.getApplicationLabel(appInfo).toString()
                            if (label.isNotBlank()) resolvedName = label
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Setup Header Icon (Real App Icon or Themed Vector Fallback)
        if (resolvedIcon != null) {
            b.ivDialogIcon.setImageDrawable(resolvedIcon)
            b.ivDialogIcon.colorFilter = null
            val iconSize = (34 * resources.displayMetrics.density).toInt()
            b.ivDialogIcon.layoutParams.width = iconSize
            b.ivDialogIcon.layoutParams.height = iconSize
        } else {
            b.ivDialogIcon.setImageResource(R.drawable.ic_external)
            val primaryColor = MaterialColors.getColor(
                b.ivDialogIcon,
                com.google.android.material.R.attr.colorPrimary
            )
            b.ivDialogIcon.setColorFilter(primaryColor)
            val iconSize = (26 * resources.displayMetrics.density).toInt()
            b.ivDialogIcon.layoutParams.width = iconSize
            b.ivDialogIcon.layoutParams.height = iconSize
        }

        // Setup Dynamic Title & Subtitle
        if (!resolvedName.isNullOrBlank()) {
            b.tvDialogTitle.text = getString(R.string.open_in_specific_app_title, resolvedName)
        } else {
            b.tvDialogTitle.text = getString(R.string.open_in_app_prompt_title)
        }
        b.tvDialogMessage.text = getString(R.string.open_in_app_prompt_desc)

        // Setup Target URL / App Info Card
        val targetUrl = intent?.dataString
            ?: intent?.data?.toString()
            ?: intent?.`package`?.let { "package:$it" }

        if (!resolvedName.isNullOrBlank() || !targetUrl.isNullOrBlank()) {
            b.targetAppCard.visibility = View.VISIBLE
            b.tvTargetAppName.text = resolvedName ?: getString(R.string.open_in_app_action)
            if (!targetUrl.isNullOrBlank()) {
                b.tvTargetUrl.visibility = View.VISIBLE
                b.tvTargetUrl.text = targetUrl
            } else {
                b.tvTargetUrl.visibility = View.GONE
            }
        } else {
            b.targetAppCard.visibility = View.GONE
        }

        // Action: Stay In Onyx
        b.btnStayInOnyx.setOnClickListener {
            actionTaken = true
            onStayInOnyx?.invoke()
            dismissAllowingStateLoss()
        }

        // Action: Open in app
        b.btnOpenInApp.setOnClickListener {
            actionTaken = true
            if (intent != null) {
                try {
                    requireContext().startActivity(intent)
                    onOpenInApp?.invoke()
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), "No application found to handle this request", Toast.LENGTH_SHORT).show()
                    onStayInOnyx?.invoke()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Could not open app: ${e.message}", Toast.LENGTH_SHORT).show()
                    onStayInOnyx?.invoke()
                }
            }
            dismissAllowingStateLoss()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!actionTaken) {
            onStayInOnyx?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxAllowedWidth = (400 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(maxAllowedWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OpenInAppPromptDialog"

        fun newInstance(
            intent: Intent,
            appName: String? = null,
            onStayInOnyx: (() -> Unit)? = null,
            onOpenInApp: (() -> Unit)? = null
        ) = OpenInAppPromptDialog().apply {
            this.targetIntent = intent
            this.appName = appName
            this.onStayInOnyx = onStayInOnyx
            this.onOpenInApp = onOpenInApp
        }
    }
}
