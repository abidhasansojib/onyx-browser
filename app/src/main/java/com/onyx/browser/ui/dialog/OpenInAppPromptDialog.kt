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
import android.content.res.ColorStateList
import androidx.core.widget.ImageViewCompat
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
        if (targetIntent == null) {
            targetIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                arguments?.getParcelable(ARG_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                arguments?.getParcelable(ARG_INTENT)
            }
        }
        if (appName == null) {
            appName = arguments?.getString(ARG_APP_NAME)
        }
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
                    val label = resolveInfo.loadLabel(pm).toString()
                    if (label.isNotBlank()) resolvedName = label
                }
            } catch (_: Exception) {}

            if (resolvedIcon == null) {
                val pkg = intent.`package`
                if (!pkg.isNullOrBlank()) {
                    try {
                        resolvedIcon = pm.getApplicationIcon(pkg)
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val label = pm.getApplicationLabel(appInfo).toString()
                        if (label.isNotBlank()) resolvedName = label
                    } catch (_: Exception) {}
                }
            }
        }

        // Setup Header Icon (Real App Icon or Themed Vector Fallback)
        if (resolvedIcon != null) {
            b.ivDialogIcon.setImageDrawable(resolvedIcon)
            b.ivDialogIcon.imageTintList = null
            ImageViewCompat.setImageTintList(b.ivDialogIcon, null)
            b.ivDialogIcon.colorFilter = null
            val iconSize = (36 * resources.displayMetrics.density).toInt()
            b.ivDialogIcon.layoutParams.width = iconSize
            b.ivDialogIcon.layoutParams.height = iconSize
        } else {
            b.ivDialogIcon.setImageResource(R.drawable.ic_external)
            val primaryColor = MaterialColors.getColor(
                b.ivDialogIcon,
                com.google.android.material.R.attr.colorPrimary
            )
            val tintList = ColorStateList.valueOf(primaryColor)
            b.ivDialogIcon.imageTintList = tintList
            ImageViewCompat.setImageTintList(b.ivDialogIcon, tintList)
            val iconSize = (26 * resources.displayMetrics.density).toInt()
            b.ivDialogIcon.layoutParams.width = iconSize
            b.ivDialogIcon.layoutParams.height = iconSize
        }

        // Setup Dynamic Title & Subtitle (Avoid exposing raw Java/Android package identifiers to users)
        val isRawPackageName = resolvedName?.contains(".") == true && !resolvedName.contains(" ")
        if (!resolvedName.isNullOrBlank() && !isRawPackageName) {
            b.tvDialogTitle.text = getString(R.string.open_in_specific_app_title, resolvedName)
        } else {
            b.tvDialogTitle.text = getString(R.string.open_in_app_prompt_title)
        }
        b.tvDialogMessage.text = getString(R.string.open_in_app_prompt_desc)

        // Setup Target URL / App Info Card
        val targetUrl = intent?.dataString
            ?: intent?.data?.toString()
            ?: intent?.`package`?.let { "package:$it" }

        val displayTargetName = if (!resolvedName.isNullOrBlank() && !isRawPackageName) {
            resolvedName
        } else {
            getString(R.string.open_in_app_action)
        }

        if (!targetUrl.isNullOrBlank() || (!resolvedName.isNullOrBlank() && !isRawPackageName)) {
            b.targetAppCard.visibility = View.VISIBLE
            b.tvTargetAppName.text = displayTargetName
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

    fun showSafely(manager: androidx.fragment.app.FragmentManager) {
        if (manager.isStateSaved || manager.isDestroyed) return
        if (manager.findFragmentByTag(TAG) != null) return
        try {
            show(manager, TAG)
        } catch (_: Exception) {}
    }

    companion object {
        const val TAG = "OpenInAppPromptDialog"
        private const val ARG_INTENT = "arg_intent"
        private const val ARG_APP_NAME = "arg_app_name"

        fun newInstance(
            intent: Intent,
            appName: String? = null,
            onStayInOnyx: (() -> Unit)? = null,
            onOpenInApp: (() -> Unit)? = null
        ) = OpenInAppPromptDialog().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_INTENT, intent)
                putString(ARG_APP_NAME, appName)
            }
            this.targetIntent = intent
            this.appName = appName
            this.onStayInOnyx = onStayInOnyx
            this.onOpenInApp = onOpenInApp
        }
    }
}
