package com.example.keyboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ClipboardItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardScreen(
    clipboardItems: List<ClipboardItem>,
    onInsertText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDeleteItem: ((String) -> Unit)? = null,
    onDeleteItems: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isClipboardMode by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItemIds by remember { mutableStateOf(setOf<String>()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("keyboard_root"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Keyboard Top Quick Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isClipboardMode && isSelectionMode) {
                    // Selection Mode Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isSelectionMode = false
                                selectedItemIds = emptySet()
                            },
                            modifier = Modifier.testTag("cancel_selection_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Selection"
                            )
                        }
                        Text(
                            text = "Selected (${selectedItemIds.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                selectedItemIds = clipboardItems.map { it.id }.toSet()
                            },
                            modifier = Modifier.testTag("select_all_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = "Select All"
                            )
                        }
                        IconButton(
                            onClick = {
                                if (selectedItemIds.isNotEmpty()) {
                                    onDeleteItems?.invoke(selectedItemIds.toList())
                                    isSelectionMode = false
                                    selectedItemIds = emptySet()
                                }
                            },
                            enabled = selectedItemIds.isNotEmpty(),
                            modifier = Modifier.testTag("delete_selected_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (selectedItemIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    // Standard Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isClipboardMode = !isClipboardMode
                                if (!isClipboardMode) {
                                    isSelectionMode = false
                                    selectedItemIds = emptySet()
                                }
                            },
                            modifier = Modifier.testTag("toggle_clipboard_btn")
                        ) {
                            Icon(
                                imageVector = if (isClipboardMode) Icons.Default.Keyboard else Icons.Default.Assignment,
                                contentDescription = if (isClipboardMode) "Switch to Keyboard" else "Open Clipboard History",
                                tint = if (isClipboardMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = if (isClipboardMode) "Clipboard History (${clipboardItems.size})" else "Keyboard",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isClipboardMode) {
                        Text(
                            text = "Long press to select",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Keyboard Body (Clipboard Panel vs Standard QWERTY Layout)
            if (isClipboardMode) {
                ClipboardPanel(
                    clipboardItems = clipboardItems,
                    isSelectionMode = isSelectionMode,
                    selectedItemIds = selectedItemIds,
                    onItemClick = { item ->
                        if (isSelectionMode) {
                            selectedItemIds = if (selectedItemIds.contains(item.id)) {
                                selectedItemIds - item.id
                            } else {
                                selectedItemIds + item.id
                            }
                            if (selectedItemIds.isEmpty()) {
                                isSelectionMode = false
                            }
                        } else {
                            onInsertText(item.content)
                        }
                    },
                    onItemLongClick = { item ->
                        isSelectionMode = true
                        selectedItemIds = selectedItemIds + item.id
                    }
                )
            } else {
                QwertyKeyboardLayout(
                    isShiftActive = isShiftActive,
                    onToggleShift = { isShiftActive = !isShiftActive },
                    onKeyClick = { char ->
                        val textToCommit = if (isShiftActive) char.uppercase() else char.lowercase()
                        onInsertText(textToCommit)
                        if (isShiftActive) isShiftActive = false // Auto unshift after letter input
                    },
                    onSpace = { onInsertText(" ") },
                    onBackspace = onBackspace,
                    onEnter = onEnter
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardPanel(
    clipboardItems: List<ClipboardItem>,
    isSelectionMode: Boolean = false,
    selectedItemIds: Set<String> = emptySet(),
    onItemClick: (ClipboardItem) -> Unit,
    onItemLongClick: (ClipboardItem) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 4.dp)
            .testTag("clipboard_panel")
    ) {
        if (clipboardItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Assignment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "No clipboard history found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = clipboardItems,
                    key = { it.id }
                ) { item ->
                    val isSelected = selectedItemIds.contains(item.id)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onItemClick(item) },
                                onLongClick = { onItemLongClick(item) }
                            )
                            .testTag("clipboard_item_${item.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelectionMode) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = if (isSelected) "Selected" else "Not Selected",
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .padding(end = 6.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = item.sourceDeviceName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text(
                                        text = item.type,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.isPinned) {
                                    Icon(
                                        imageVector = Icons.Default.Pin,
                                        contentDescription = "Pinned",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (item.isFavorite) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Favorite",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QwertyKeyboardLayout(
    isShiftActive: Boolean,
    onToggleShift: () -> Unit,
    onKeyClick: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit
) {
    val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("qwerty_layout"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            row1.forEach { char ->
                KeyButton(
                    text = if (isShiftActive) char.uppercase() else char,
                    onClick = { onKeyClick(char) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            row2.forEach { char ->
                KeyButton(
                    text = if (isShiftActive) char.uppercase() else char,
                    onClick = { onKeyClick(char) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shift Key
            SpecialKeyButton(
                text = "⇧",
                isHighlighted = isShiftActive,
                onClick = onToggleShift,
                modifier = Modifier
                    .weight(1.5f)
                    .testTag("key_shift")
            )

            row3.forEach { char ->
                KeyButton(
                    text = if (isShiftActive) char.uppercase() else char,
                    onClick = { onKeyClick(char) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Backspace Key with continuous repeating acceleration gesture
            SpecialKeyButton(
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onBackspace,
                isRepeating = true,
                modifier = Modifier
                    .weight(1.5f)
                    .testTag("key_backspace")
            )
        }

        // Row 4 (Bottom controls)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpecialKeyButton(
                text = "123",
                onClick = { onKeyClick("1") },
                modifier = Modifier.weight(1.5f)
            )

            SpecialKeyButton(
                text = ",",
                onClick = { onKeyClick(",") },
                modifier = Modifier.weight(1f)
            )

            // Space Bar
            Box(
                modifier = Modifier
                    .weight(4f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onSpace() }
                    .testTag("key_space"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "space",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SpecialKeyButton(
                text = ".",
                onClick = { onKeyClick(".") },
                modifier = Modifier.weight(1f)
            )

            // Enter Key
            SpecialKeyButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardReturn,
                        contentDescription = "Enter",
                        modifier = Modifier.size(18.dp)
                    )
                },
                isHighlighted = true,
                onClick = onEnter,
                modifier = Modifier
                    .weight(1.5f)
                    .testTag("key_enter")
            )
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .testTag("key_$text"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

fun Modifier.repeatingClickable(
    initialDelayMillis: Long = 350L,
    minDelayMillis: Long = 40L,
    onClick: () -> Unit
): Modifier = this.pointerInput(Unit) {
    coroutineScope {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onClick()
            val job = launch {
                delay(initialDelayMillis)
                var currentDelay = 120L
                while (true) {
                    onClick()
                    delay(currentDelay)
                    if (currentDelay > minDelayMillis) {
                        currentDelay = (currentDelay * 0.85f).toLong().coerceAtLeast(minDelayMillis)
                    }
                }
            }
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            job.cancel()
        }
    }
}

@Composable
fun SpecialKeyButton(
    text: String? = null,
    icon: (@Composable () -> Unit)? = null,
    isHighlighted: Boolean = false,
    isRepeating: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clickModifier = if (isRepeating) {
        Modifier.repeatingClickable(onClick = onClick)
    } else {
        Modifier.clickable { onClick() }
    }

    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isHighlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else icon?.invoke()
    }
}
