package com.shabana.filekhoj.pdf

import android.content.Context
import android.graphics.Bitmap
import com.shabana.filekhoj.data.DocumentEntity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val editorGreen = Color(0xFF07583D)
private val editorGold = Color(0xFFD6A83B)

@Composable
fun PdfEditorScreen(
    context: Context,
    document: DocumentEntity,
    onClose: () -> Unit,
    onSaveCopy: (File) -> Unit
) {
    var session by remember(document.id) { mutableStateOf<PdfEditorSession?>(null) }
    var pages by remember(document.id) { mutableStateOf(emptyList<PdfEditorPage>()) }
    var originalPages by remember(document.id) { mutableStateOf(emptyList<PdfEditorPage>()) }
    var thumbnails by remember(document.id) { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var selectedPreviewIndex by remember(document.id) { mutableStateOf(0) }
    var selectedPageIndices by remember(document.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var undoStack by remember(document.id) { mutableStateOf<List<List<PdfEditorPage>>>(emptyList()) }
    var redoStack by remember(document.id) { mutableStateOf<List<List<PdfEditorPage>>>(emptyList()) }
    var isLoading by remember(document.id) { mutableStateOf(true) }
    var isSaving by remember(document.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(document.id) {
        isLoading = true
        loadError = null
        try {
            val loaded = withContext(Dispatchers.IO) { PdfEditorSession.open(context, document) }
            session = loaded
            val initial = PdfEditorPageOperations.initial(loaded.pageCount)
            pages = initial
            originalPages = initial
            isLoading = false

            val previewMap = withContext(Dispatchers.IO) {
                buildMap {
                    for (index in 0 until minOf(loaded.pageCount, 48)) {
                        val thumbnail = runCatching {
                            loaded.renderThumbnail(index, 150)
                        }.getOrNull()
                        if (thumbnail != null) put(index, thumbnail)
                    }
                }
            }
            thumbnails = previewMap
            message = if (loaded.pageCount > 48) {
                "Thumbnails show the first 48 pages. All pages remain editable."
            } else {
                "Select pages, rotate them, or use ↑ and ↓ to change their order."
            }
        } catch (error: Exception) {
            isLoading = false
            loadError = error.message ?: "FileKhoj could not open this PDF."
        }
    }

    LaunchedEffect(session, selectedPreviewIndex, pages) {
        val activeSession = session ?: return@LaunchedEffect
        val activePage = pages.firstOrNull { it.sourceIndex == selectedPreviewIndex } ?: return@LaunchedEffect
        try {
            previewBitmap = withContext(Dispatchers.IO) {
                activeSession.renderThumbnail(activePage.sourceIndex, 900)
            }
        } catch (error: Exception) {
            message = error.message ?: "Could not render this page preview."
        }
    }

    DisposableEffect(session) {
        onDispose { session?.close() }
    }

    fun updatePages(updated: List<PdfEditorPage>) {
        if (updated == pages) return
        undoStack = (undoStack + listOf(pages)).takeLast(40)
        redoStack = emptyList()
        pages = updated
        selectedPageIndices = selectedPageIndices.intersect(updated.map { it.sourceIndex }.toSet())
        if (selectedPreviewIndex !in updated.map { it.sourceIndex }) {
            selectedPreviewIndex = updated.firstOrNull()?.sourceIndex ?: 0
        }
        message = null
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack = (redoStack + listOf(pages)).takeLast(40)
        pages = undoStack.last()
        undoStack = undoStack.dropLast(1)
        selectedPageIndices = emptySet()
        selectedPreviewIndex = pages.first().sourceIndex
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack = (undoStack + listOf(pages)).takeLast(40)
        pages = redoStack.last()
        redoStack = redoStack.dropLast(1)
        selectedPageIndices = emptySet()
        selectedPreviewIndex = pages.first().sourceIndex
    }

    val isDirty = pages != originalPages

    Dialog(
        onDismissRequest = {
            if (isDirty) showDiscardConfirmation = true else onClose()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = Color(0xFFF6F7F3)) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(editorGreen).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("PDF Editor", color = Color.White,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(document.displayName, color = Color(0xFFE0EDE5),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = {
                            if (isDirty) showDiscardConfirmation = true else onClose()
                        }) { Text("Close", color = Color.White) }
                    }

                    if (isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(color = editorGreen)
                                Text("Opening PDF and preparing page previews…")
                            }
                        }
                    } else if (loadError != null) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Could not open PDF", style = MaterialTheme.typography.titleLarge)
                                Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                                Button(onClick = onClose) { Text("Back to documents") }
                            }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Page preview", Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Page ${pages.indexOfFirst { it.sourceIndex == selectedPreviewIndex } + 1} of ${pages.size}",
                                            style = MaterialTheme.typography.labelMedium, color = editorGreen
                                        )
                                    }
                                    Box(
                                        Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 220.dp)
                                            .clip(RoundedCornerShape(10.dp)).background(Color(0xFFF0F1EE)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (previewBitmap != null) {
                                            val rotation = pages.firstOrNull {
                                                it.sourceIndex == selectedPreviewIndex
                                            }?.rotationDelta ?: 0
                                            Image(
                                                bitmap = previewBitmap!!.asImageBitmap(),
                                                contentDescription = "Selected PDF page preview",
                                                modifier = Modifier.fillMaxSize().padding(6.dp)
                                                    .graphicsLayer { rotationZ = rotation.toFloat() },
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Text("Page preview")
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            enabled = selectedPageIndices.isNotEmpty(),
                                            onClick = {
                                                updatePages(PdfEditorPageOperations.rotate(
                                                    pages, selectedPageIndices, clockwise = false
                                                ))
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                                        ) { Text("↶ Rotate 90°", fontSize = 12.sp) }
                                        OutlinedButton(
                                            enabled = selectedPageIndices.isNotEmpty(),
                                            onClick = {
                                                updatePages(PdfEditorPageOperations.rotate(
                                                    pages, selectedPageIndices, clockwise = true
                                                ))
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                                        ) { Text("↷ Rotate 90°", fontSize = 12.sp) }
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Pages", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold)
                                    Text("${pages.size} pages • ${selectedPageIndices.size} selected",
                                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF607168))
                                }
                                TextButton(onClick = {
                                    selectedPageIndices = if (selectedPageIndices.size == pages.size) {
                                        emptySet()
                                    } else pages.map { it.sourceIndex }.toSet()
                                }) {
                                    Text(if (selectedPageIndices.size == pages.size) "Clear selection" else "Select all")
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    enabled = undoStack.isNotEmpty(), onClick = ::undo,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 3.dp)
                                ) { Text("↶ Undo", fontSize = 12.sp) }
                                OutlinedButton(
                                    enabled = redoStack.isNotEmpty(), onClick = ::redo,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 3.dp)
                                ) { Text("↷ Redo", fontSize = 12.sp) }
                                OutlinedButton(
                                    enabled = selectedPageIndices.isNotEmpty(),
                                    onClick = { showDeleteConfirmation = true },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 3.dp)
                                ) { Text("Delete", fontSize = 12.sp) }
                            }

                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(pages, key = { _, page -> page.sourceIndex }) { index, page ->
                                    val isSelected = page.sourceIndex in selectedPageIndices
                                    Card(
                                        Modifier.fillMaxWidth().border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) editorGold else Color(0xFFE2E7E1),
                                            RoundedCornerShape(12.dp)
                                        ),
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedPageIndices = if (checked) {
                                                        selectedPageIndices + page.sourceIndex
                                                    } else selectedPageIndices - page.sourceIndex
                                                }
                                            )
                                            val thumbnail = thumbnails[page.sourceIndex]
                                            if (thumbnail != null) {
                                                Image(
                                                    bitmap = thumbnail.asImageBitmap(),
                                                    contentDescription = "Thumbnail of source page ${page.sourceIndex + 1}",
                                                    modifier = Modifier.size(width = 48.dp, height = 62.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable { selectedPreviewIndex = page.sourceIndex }
                                                        .graphicsLayer { rotationZ = page.rotationDelta.toFloat() },
                                                    contentScale = ContentScale.Fit
                                                )
                                            } else {
                                                Box(
                                                    Modifier.size(width = 48.dp, height = 62.dp)
                                                        .background(Color(0xFFF0F1EE), RoundedCornerShape(4.dp))
                                                        .clickable { selectedPreviewIndex = page.sourceIndex },
                                                    contentAlignment = Alignment.Center
                                                ) { Text("PDF", color = editorGreen, fontSize = 11.sp) }
                                            }
                                            Column(Modifier.weight(1f).clickable {
                                                selectedPreviewIndex = page.sourceIndex
                                            }) {
                                                Text("Page ${index + 1}", style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    if (page.rotationDelta == 0) "Source page ${page.sourceIndex + 1}"
                                                    else "Rotation ${page.rotationDelta}°",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF69776F)
                                                )
                                            }
                                            TextButton(
                                                enabled = index > 0,
                                                onClick = {
                                                    updatePages(PdfEditorPageOperations.move(pages, index, index - 1))
                                                }
                                            ) { Text("↑") }
                                            TextButton(
                                                enabled = index < pages.lastIndex,
                                                onClick = {
                                                    updatePages(PdfEditorPageOperations.move(pages, index, index + 1))
                                                }
                                            ) { Text("↓") }
                                        }
                                    }
                                }
                            }

                            message?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52685B))
                            }
                            Text(
                                "Your original PDF is never overwritten. Save creates a separate copy. Editing a digitally signed file may invalidate its signature on the edited copy.",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF64756C)
                            )
                            Button(
                                onClick = {
                                    val activeSession = session ?: return@Button
                                    isSaving = true
                                    message = "Preparing and verifying edited PDF…"
                                    val currentPages = pages
                                    scope.launch {
                                        try {
                                            val output = withContext(Dispatchers.IO) {
                                                activeSession.exportCopy(currentPages)
                                            }
                                            message = "Copy is ready. Choose where to save it."
                                            onSaveCopy(output)
                                        } catch (error: Exception) {
                                            message = error.message ?: "Could not export this PDF."
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                },
                                enabled = session != null && !isSaving && pages.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (isSaving) "Preparing copy…" else "Save as PDF copy") }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove selected pages?") },
            text = { Text("This removes ${selectedPageIndices.size} selected page(s) from the edited copy. The original PDF is unaffected.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        updatePages(PdfEditorPageOperations.delete(pages, selectedPageIndices))
                        selectedPageIndices = emptySet()
                    } catch (error: IllegalArgumentException) {
                        message = error.message
                    }
                    showDeleteConfirmation = false
                }) { Text("Remove pages") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") } }
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard PDF edits?") },
            text = { Text("Your original document is safe. Unsaved page changes will be discarded.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirmation = false
                    onClose()
                }) { Text("Discard edits") }
            },
            dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text("Continue editing") } }
        )
    }
}
