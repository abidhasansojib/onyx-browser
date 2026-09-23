package com.onyx.browser.ui.dialog

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.onyx.browser.databinding.DialogOpenInAppPromptBinding

class OpenInAppPromptDialog : DialogFragment() {

    private var _binding: DialogOpenInAppPromptBinding? = null
    private val binding get() = _binding!!

    var targetIntent: Intent? = null
    var appName: String? = null
    var onStayInOnyx: (() -> Unit)? = null
    var onOpenInApp: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        _binding = DialogOpenInAppPromptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val name = appName
        if (!name.isNullOrBlank()) {
            binding.targetAppPill.visibility = View.VISIBLE
            binding.tvTargetAppName.text = name
        } else {
            binding.targetAppPill.visibility = View.GONE
        }

        binding.btnStayInOnyx.setOnClickListener {
            onStayInOnyx?.invoke()
            dismiss()
        }

        binding.btnOpenInApp.setOnClickListener {
            val intent = targetIntent
            if (intent != null) {
                try {
                    requireContext().startActivity(intent)
                    onOpenInApp?.invoke()
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), "No application found to handle this request", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Could not open app: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
