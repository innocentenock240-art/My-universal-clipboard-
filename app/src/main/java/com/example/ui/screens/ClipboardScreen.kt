package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ClipboardItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(
    items: List<ClipboardItem>,
    isCaptureActive: Boolean = true,
    onAddItem: (String) -> Unit,
    onAddRichItem: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit = { _, _, _, _, _ -> },
    onCopyItem: (String) -> Unit,
    onCopyClipboardItem: (ClipboardItem) -> Unit = { onCopyItem(it.content) },
    onToggleFavorite: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit = {},
    onCheckClipboard: () -> Unit = {},
    onToggleCapture: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterTab by remember { mutableStateOf(0) } // 0: All, 1: Favorites, 2: Pinned
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // Check system clipboard upon entering this screen
    LaunchedEffect(Unit) {
        onCheckClipboard()
    }

    val filteredItems = remember(items, searchQuery, selectedFilterTab) {
        items.filter { item ->
            val matchesSearch = item.content.contains(searchQuery, ignoreCase = true) ||
                    item.sourceDeviceName.contains(searchQuery, ignoreCase = true) ||
                    (item.fileName?.contains(searchQuery, ignoreCase = true) == true) ||
                    item.type.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (selectedFilterTab) {
                1 -> item.isFavorite
                2 -> item.isPinned
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clipboard History", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onCheckClipboard) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = "Check System Clipboard",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear All History",
                                tint = MaterialTheme.colorScheme.error
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
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_clipboard_fab"),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Clipboard Item",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Privacy & Active Capture Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isCaptureActive)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = if (isCaptureActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isCaptureActive) "Clipboard capture: Active (Foreground)" else "Clipboard capture: Stopped",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = isCaptureActive,
                        onCheckedChange = { onToggleCapture() },
                        modifier = Modifier.testTag("toggle_capture_switch")
                    )
                }
                Text(
                    text = "Rich content support active: Plain text, Unicode, URLs, Code, HTML, Images, and Files with SHA-256 integrity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_clipboard_input"),
                placeholder = { Text("Search text, URLs, code, files...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Filter Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedFilterTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedFilterTab == 0,
                    onClick = { selectedFilterTab = 0 },
                    text = { Text("All (${items.size})") }
                )
                Tab(
                    selected = selectedFilterTab == 1,
                    onClick = { selectedFilterTab = 1 },
                    text = { Text("Favorites (${items.count { it.isFavorite }})") }
                )
                Tab(
                    selected = selectedFilterTab == 2,
                    onClick = { selectedFilterTab = 2 },
                    text = { Text("Pinned (${items.count { it.isPinned }})") }
                )
            }

            // List of items
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (items.isEmpty()) "No clipboard items yet." else "No matching clipboard items found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        ClipboardItemCard(
                            item = item,
                            onCopy = { onCopyClipboardItem(item) },
                            onToggleFavorite = { onToggleFavorite(item.id) },
                            onTogglePin = { onTogglePin(item.id) },
                            onDelete = { onDeleteItem(item.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddRichClipboardItemDialog(
            onDismiss = { showAddDialog = false },
            onConfirmText = { text ->
                onAddItem(text)
                showAddDialog = false
            },
            onConfirmRich = { type, content, mimeType, fileName, sizeBytes ->
                onAddRichItem(type, content, mimeType, fileName, sizeBytes)
                showAddDialog = false
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Clipboard History") },
            text = { Text("Are you sure you want to delete all saved items from your local database?") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ClipboardItemCard(
    item: ClipboardItem,
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("item_card_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Type Badge + Device Name + Favorite/Pin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(item.type, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        icon = {
                            Icon(
                                imageVector = when (item.type) {
                                    ClipboardItem.TYPE_URL -> Icons.Default.Link
                                    ClipboardItem.TYPE_CODE -> Icons.Default.Code
                                    ClipboardItem.TYPE_HTML -> Icons.Default.Html
                                    ClipboardItem.TYPE_IMAGE -> Icons.Default.Image
                                    ClipboardItem.TYPE_FILE -> Icons.Default.AttachFile
                                    else -> Icons.Default.TextFields
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    Text(
                        text = item.sourceDeviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (item.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (item.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin",
                            tint = if (item.isPinned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Content Body Based on Type
            when (item.type) {
                ClipboardItem.TYPE_IMAGE -> {
                    val bitmap = remember(item.content) {
                        try {
                            val decodedBytes = Base64.decode(item.content, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = item.fileName ?: "Clipboard Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (item.fileName != null) {
                        Text(
                            text = item.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                ClipboardItem.TYPE_FILE -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    text = item.fileName ?: "Attached File",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${item.mimeType} • ${item.displaySize}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                ClipboardItem.TYPE_CODE -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                else -> {
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Footer Row: Metadata & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${item.displaySize} • SHA-256: ${item.hash.take(8)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = onCopy,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Copy", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AddRichClipboardItemDialog(
    onDismiss: () -> Unit,
    onConfirmText: (String) -> Unit,
    onConfirmRich: (type: String, content: String, mimeType: String, fileName: String?, sizeBytes: Long) -> Unit
) {
    var selectedTypeIndex by remember { mutableStateOf(0) } // 0: Text, 1: URL, 2: Code, 3: HTML, 4: Image, 5: File
    var textInput by remember { mutableStateOf("") }
    var fileNameInput by remember { mutableStateOf("") }

    val typeOptions = listOf("Text", "URL", "Code", "HTML", "Image", "File")

    // Prepopulate sensible template when switching tabs
    LaunchedEffect(selectedTypeIndex) {
        when (selectedTypeIndex) {
            0 -> if (textInput.isBlank()) textInput = "Hello Universal Clipboard! 🚀"
            1 -> if (textInput.isBlank()) textInput = "https://example.com/api?user=alpha#dashboard"
            2 -> if (textInput.isBlank()) textInput = "fun synchronizeClipboard(item: ClipboardItem) {\n    syncEngine.broadcast(item)\n}"
            3 -> if (textInput.isBlank()) textInput = "<div style=\"color: #0066cc;\"><h1>Universal Clipboard</h1><p>Rich HTML content</p></div>"
            4 -> {
                fileNameInput = "sample_badge.png"
                // 1x1 transparent PNG Base64 for instant testing
                textInput = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkWPjfDwAEeQHzE1LqtwAAAABJRU5ErkJggg=="
            }
            5 -> {
                fileNameInput = "document.pdf"
                textInput = "JVBERi0xLjQKJeLjz9MKMSAwIG9iaiA8PC9UeXBlL0NhdGFsb2cvUGFnZXMgMiAwIFI+PmVuZG9iagoyIDAgb2JqIDw8L1R5cGUvUGFnZXMvS2lkc1szIDAgUl0vQ291bnQgMT4+ZW5kb2JqCjMgMCBvYmo8PC9UeXBlL1BhZ2UvUGFyZW50IDIgMCBSL01lZGlhQm94WzAgMCA2MTIgNzkyXT4+ZW5kb2JqCnhyZWYKMCA0Cg=="
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Rich Clipboard Item") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Type selector chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    typeOptions.forEachIndexed { index, title ->
                        FilterChip(
                            selected = selectedTypeIndex == index,
                            onClick = {
                                selectedTypeIndex = index
                                textInput = ""
                            },
                            label = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }

                if (selectedTypeIndex == 4 || selectedTypeIndex == 5) {
                    OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("File Name") },
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    label = {
                        Text(
                            when (selectedTypeIndex) {
                                1 -> "URL Link"
                                2 -> "Source Code Snippet"
                                3 -> "HTML Content"
                                4 -> "Image Payload (Base64)"
                                5 -> "File Payload (Base64/Text)"
                                else -> "Text or Unicode Content"
                            }
                        )
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedTypeIndex) {
                        0, 1 -> onConfirmText(textInput)
                        2 -> onConfirmRich(ClipboardItem.TYPE_CODE, textInput, ClipboardItem.MIME_TEXT_PLAIN, null, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                        3 -> onConfirmRich(ClipboardItem.TYPE_HTML, textInput, ClipboardItem.MIME_TEXT_HTML, null, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                        4 -> onConfirmRich(ClipboardItem.TYPE_IMAGE, textInput, ClipboardItem.MIME_IMAGE_PNG, fileNameInput.ifBlank { "image.png" }, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                        5 -> onConfirmRich(ClipboardItem.TYPE_FILE, textInput, ClipboardItem.MIME_OCTET_STREAM, fileNameInput.ifBlank { "file.dat" }, textInput.toByteArray(Charsets.UTF_8).size.toLong())
                    }
                },
                enabled = textInput.isNotBlank()
            ) {
                Text("Add & Sync")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

