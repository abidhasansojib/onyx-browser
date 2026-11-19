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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.ui.theme.OnyxTheme

class OpenInAppPromptDialog : DialogFragment() {

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
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OnyxTheme {
                    OpenInAppPromptContent()
                }
            }
        }
    }

    @Composable
    private fun OpenInAppPromptContent() {
        val context = LocalContext.current
        val intent = targetIntent
        val pm = context.packageManager

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

        val iconBitmap = remember(resolvedIcon) {
            try {
                resolvedIcon?.toBitmap()
            } catch (_: Exception) {
                null
            }
        }

        val isRawPackageName = resolvedName?.contains(".") == true && !resolvedName.contains(" ")
        val titleText = if (!resolvedName.isNullOrBlank() && !isRawPackageName) {
            stringResource(R.string.open_in_specific_app_title, resolvedName)
        } else {
            stringResource(R.string.open_in_app_prompt_title)
        }

        val targetUrl = intent?.dataString
            ?: intent?.data?.toString()
            ?: intent?.`package`?.let { "package:$it" }

        val displayTargetName = if (!resolvedName.isNullOrBlank() && !isRawPackageName) {
            resolvedName
        } else {
            stringResource(R.string.open_in_app_action)
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = displayTargetName,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_external),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.open_in_app_prompt_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!targetUrl.isNullOrBlank() || (!resolvedName.isNullOrBlank() && !isRawPackageName)) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = displayTargetName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!targetUrl.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = targetUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            actionTaken = true
                            onStayInOnyx?.invoke()
                            dismissAllowingStateLoss()
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.stay_in_onyx),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            actionTaken = true
                            if (intent != null) {
                                try {
                                    context.startActivity(intent)
                                    onOpenInApp?.invoke()
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "No application found to handle this request", Toast.LENGTH_SHORT).show()
                                    onStayInOnyx?.invoke()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open app: ${e.message}", Toast.LENGTH_SHORT).show()
                                    onStayInOnyx?.invoke()
                                }
                            }
                            dismissAllowingStateLoss()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.open_in_app_action))
                    }
                }
            }
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
        ): OpenInAppPromptDialog {
            return OpenInAppPromptDialog().apply {
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
}
