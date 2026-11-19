package com.onyx.browser.ui.menu

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.ui.theme.OnyxTheme
import java.net.URLEncoder

/**
 * Bottom sheet that lets the user pick a reverse image search engine in Compose.
 */
class ImageSearchPickerBottomSheet(
    private val imageUrl: String,
    private val onEngineSelected: (searchUrl: String, engineName: String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OnyxTheme {
                    ImageSearchPickerContent()
                }
            }
        }
    }

    @Composable
    private fun ImageSearchPickerContent() {
        val encoded = remember(imageUrl) {
            try {
                URLEncoder.encode(imageUrl, "UTF-8")
            } catch (_: Exception) {
                imageUrl
            }
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .navigationBarsPadding()
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 16.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                Text(
                    text = "Search by image",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                SearchEngineRow(
                    name = "Google Lens",
                    desc = "Visual match & object recognition",
                    onClick = {
                        val url = "https://lens.google.com/uploadbyurl?url=$encoded"
                        onEngineSelected(url, "Google Lens")
                        dismiss()
                    }
                )

                SearchEngineRow(
                    name = "TinEye",
                    desc = "Exact duplicate image finder",
                    onClick = {
                        val url = "https://tineye.com/search/?url=$encoded"
                        onEngineSelected(url, "TinEye")
                        dismiss()
                    }
                )

                SearchEngineRow(
                    name = "Yandex Images",
                    desc = "High-precision face & scene search",
                    onClick = {
                        val url = "https://yandex.com/images/search?rpt=imageview&url=$encoded"
                        onEngineSelected(url, "Yandex Images")
                        dismiss()
                    }
                )

                SearchEngineRow(
                    name = "Bing Visual Search",
                    desc = "Microsoft visual image lookup",
                    onClick = {
                        val url = "https://www.bing.com/images/search?view=detailv2&iss=sbi&FORM=SBIIDP&q=imgurl:$encoded"
                        onEngineSelected(url, "Bing Visual Search")
                        dismiss()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { dismiss() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }

    @Composable
    private fun SearchEngineRow(
        name: String,
        desc: String,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ImageSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    companion object {
        const val TAG = "ImageSearchPickerSheet"
    }
}

typealias ImageSearchPickerSheet = ImageSearchPickerBottomSheet
