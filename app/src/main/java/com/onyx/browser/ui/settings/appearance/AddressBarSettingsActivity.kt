package com.onyx.browser.ui.settings.appearance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.SwitchPreference
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme

class AddressBarSettingsActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private var isAddressBarAtBottom by mutableStateOf(false)
    private var isHomeReplacedWithRefresh by mutableStateOf(false)
    private var isAutoHideToolbar by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        isAddressBarAtBottom = preferences.isAddressBarAtBottom
        isHomeReplacedWithRefresh = preferences.isHomeReplacedWithRefresh
        isAutoHideToolbar = preferences.isAutoHideToolbarEnabled

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                AddressBarSettingsScreen(
                    onBackClick = { finish() },
                    isBottom = isAddressBarAtBottom,
                    onPositionSelect = { atBottom ->
                        isAddressBarAtBottom = atBottom
                        preferences.isAddressBarAtBottom = atBottom
                    },
                    isHomeRefresh = isHomeReplacedWithRefresh,
                    onHomeRefreshChange = { enabled ->
                        isHomeReplacedWithRefresh = enabled
                        preferences.isHomeReplacedWithRefresh = enabled
                    },
                    isAutoHide = isAutoHideToolbar,
                    onAutoHideChange = { enabled ->
                        isAutoHideToolbar = enabled
                        preferences.isAutoHideToolbarEnabled = enabled
                    }
                )
            }
        }
    }
}

@Composable
fun AddressBarSettingsScreen(
    onBackClick: () -> Unit,
    isBottom: Boolean,
    onPositionSelect: (Boolean) -> Unit,
    isHomeRefresh: Boolean,
    onHomeRefreshChange: (Boolean) -> Unit,
    isAutoHide: Boolean,
    onAutoHideChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Address Bar Position",
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            CategoryHeader("Placement")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AddressBarPositionCard(
                    title = "Top",
                    subtitle = "Classic desktop style",
                    isAddressBarBottom = false,
                    isSelected = !isBottom,
                    onClick = { onPositionSelect(false) },
                    modifier = Modifier.weight(1f)
                )

                AddressBarPositionCard(
                    title = "Bottom",
                    subtitle = "One-hand thumb friendly",
                    isAddressBarBottom = true,
                    isSelected = isBottom,
                    onClick = { onPositionSelect(true) },
                    modifier = Modifier.weight(1f)
                )
            }

            CategoryHeader("Toolbar Controls")
            CardContainer {
                SwitchPreference(
                    title = "Replace Home with Refresh",
                    subtitle = "Display reload action instead of home button on the address bar",
                    checked = isHomeRefresh,
                    onCheckedChange = onHomeRefreshChange
                )

                SwitchPreference(
                    title = "Auto-Hide Toolbar on Scroll",
                    subtitle = "Dynamically collapse the address bar when scrolling down webpages",
                    checked = isAutoHide,
                    onCheckedChange = onAutoHideChange
                )
            }
        }
    }
}

@Composable
private fun AddressBarPositionCard(
    title: String,
    subtitle: String,
    isAddressBarBottom: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else MaterialTheme.colorScheme.outline,
        animationSpec = spring(),
        label = "borderColor"
    )

    Card(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Phone Miniature Preview
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp)
            ) {
                // Mock web content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                )

                // Mock Address bar representation
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .align(if (isAddressBarBottom) Alignment.BottomCenter else Alignment.TopCenter)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) activeColor else MaterialTheme.colorScheme.outline)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurface
                )

                if (isSelected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(activeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
    )
}
