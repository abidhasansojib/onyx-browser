package com.onyx.browser.ui.bookmarks

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.viewmodel.BookmarksViewModel

class BookmarksActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "com.onyx.browser.extra.URL"
        const val EXTRA_TITLE = "com.onyx.browser.extra.TITLE"
        const val EXTRA_IS_BOOKMARKLET = "com.onyx.browser.extra.IS_BOOKMARKLET"
        const val EXTRA_ACTION = "com.onyx.browser.extra.ACTION"
        const val ACTION_OPEN = "OPEN"
        const val ACTION_OPEN_NEW_TAB = "OPEN_NEW_TAB"
        const val ACTION_OPEN_INCOGNITO = "OPEN_INCOGNITO"
        const val ACTION_RUN_BOOKMARKLET = "RUN_BOOKMARKLET"
    }

    private lateinit var viewModel: BookmarksViewModel
    private lateinit var preferences: BrowserPreferences

    private var editingBookmark by mutableStateOf<BookmarkItem?>(null)
    private var isAddEditOpen by mutableStateOf(false)
    private var bookmarkToDelete by mutableStateOf<BookmarkItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel = ViewModelProvider(this)[BookmarksViewModel::class.java]
        preferences = BrowserPreferences.getInstance(this)

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            val bookmarks by viewModel.bookmarks.collectAsState()
            val searchQuery by viewModel.searchQuery.collectAsState()

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                BookmarksScreen(
                    bookmarks = bookmarks,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onBackClick = { finish() },
                    onItemClick = { item ->
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_URL, item.url)
                            putExtra(EXTRA_TITLE, item.title)
                            putExtra(EXTRA_IS_BOOKMARKLET, item.isBookmarklet)
                            putExtra(EXTRA_ACTION, if (item.isBookmarklet) ACTION_RUN_BOOKMARKLET else ACTION_OPEN)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onItemOpenNewTab = { item ->
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_URL, item.url)
                            putExtra(EXTRA_TITLE, item.title)
                            putExtra(EXTRA_IS_BOOKMARKLET, false)
                            putExtra(EXTRA_ACTION, ACTION_OPEN_NEW_TAB)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onItemOpenIncognito = { item ->
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_URL, item.url)
                            putExtra(EXTRA_TITLE, item.title)
                            putExtra(EXTRA_IS_BOOKMARKLET, false)
                            putExtra(EXTRA_ACTION, ACTION_OPEN_INCOGNITO)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onItemCopy = { item ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("URL", item.url))
                        Toast.makeText(this@BookmarksActivity, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    onItemEdit = { item ->
                        editingBookmark = item
                        isAddEditOpen = true
                    },
                    onItemDelete = { item ->
                        bookmarkToDelete = item
                    },
                    onAddBookmarkClick = {
                        editingBookmark = null
                        isAddEditOpen = true
                    }
                )

                if (isAddEditOpen) {
                    AddEditBookmarkSheet(
                        existing = editingBookmark,
                        onDismiss = { isAddEditOpen = false },
                        onSave = { title, url ->
                            if (editingBookmark != null) {
                                viewModel.updateBookmark(editingBookmark!!.id, title, url) {}
                            } else {
                                viewModel.addBookmark(title, url) {}
                            }
                            isAddEditOpen = false
                        }
                    )
                }

                if (bookmarkToDelete != null) {
                    val toDelete = bookmarkToDelete!!
                    AppAlertDialog(
                        title = "Delete bookmark?",
                        text = { Text("Are you sure you want to delete '${toDelete.title}'?") },
                        confirmButtonText = "Delete",
                        isDestructive = true,
                        onConfirm = {
                            viewModel.deleteBookmark(toDelete)
                            bookmarkToDelete = null
                        },
                        onDismissRequest = { bookmarkToDelete = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<BookmarkItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onItemClick: (BookmarkItem) -> Unit,
    onItemOpenNewTab: (BookmarkItem) -> Unit,
    onItemOpenIncognito: (BookmarkItem) -> Unit,
    onItemCopy: (BookmarkItem) -> Unit,
    onItemEdit: (BookmarkItem) -> Unit,
    onItemDelete: (BookmarkItem) -> Unit,
    onAddBookmarkClick: () -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search bookmarks...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "Bookmarks",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearchActive) {
                            isSearchActive = false
                            onSearchQueryChange("")
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddBookmarkClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Bookmark")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (bookmarks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "No bookmarks added yet" else "No bookmarks found for '$searchQuery'",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = 80.dp)
            ) {
                items(bookmarks, key = { it.id }) { item ->
                    CardContainer {
                        BookmarkItemRow(
                            item = item,
                            onClick = { onItemClick(item) },
                            onOpenNewTab = { onItemOpenNewTab(item) },
                            onOpenIncognito = { onItemOpenIncognito(item) },
                            onCopy = { onItemCopy(item) },
                            onEdit = { onItemEdit(item) },
                            onDelete = { onItemDelete(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkItemRow(
    item: BookmarkItem,
    onClick: () -> Unit,
    onOpenNewTab: () -> Unit,
    onOpenIncognito: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (item.isBookmarklet) Icons.Default.Code else Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.title.ifBlank { item.url },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Box {
            IconButton(onClick = { isMenuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Open in new tab") },
                    onClick = { isMenuOpen = false; onOpenNewTab() }
                )
                DropdownMenuItem(
                    text = { Text("Open in incognito") },
                    onClick = { isMenuOpen = false; onOpenIncognito() }
                )
                DropdownMenuItem(
                    text = { Text("Copy link") },
                    onClick = { isMenuOpen = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = { isMenuOpen = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { isMenuOpen = false; onDelete() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditBookmarkSheet(
    existing: BookmarkItem?,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = if (existing != null) "Edit Bookmark" else "Add Bookmark",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Bookmark Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL (or javascript:...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (title.isNotBlank() && url.isNotBlank()) {
                            onSave(title.trim(), url.trim())
                        }
                    },
                    enabled = title.isNotBlank() && url.isNotBlank()
                ) {
                    Text(if (existing != null) "Save" else "Add")
                }
            }
        }
    }
}
