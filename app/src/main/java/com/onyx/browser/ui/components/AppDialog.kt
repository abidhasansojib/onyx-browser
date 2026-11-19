package com.onyx.browser.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissButtonText: String? = "Cancel",
    isDestructive: Boolean = false,
    text: @Composable (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = text,
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            if (dismissButtonText != null) {
                TextButton(onClick = onDismissRequest) {
                    Text(
                        text = dismissButtonText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    )
}

@Composable
fun AppDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissButtonText: String? = "Cancel",
    isDestructive: Boolean = false,
    text: @Composable (() -> Unit)? = null
) = AppAlertDialog(title, onDismissRequest, confirmButtonText, onConfirm, modifier, dismissButtonText, isDestructive, text)

@Composable
fun OnyxAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissButtonText: String? = "Cancel",
    isDestructive: Boolean = false,
    text: @Composable (() -> Unit)? = null
) = AppAlertDialog(title, onDismissRequest, confirmButtonText, onConfirm, modifier, dismissButtonText, isDestructive, text)

