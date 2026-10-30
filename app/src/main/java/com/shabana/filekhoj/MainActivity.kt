package com.shabana.filekhoj

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shabana.filekhoj.data.DocumentEntity
import com.shabana.filekhoj.data.DocumentRepository
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TypeFilter(val label: String) {
    ALL("All types"), PDF("PDF"), IMAGE("Images"), DOCUMENT("Documents"), OTHER("Other")
}
private enum class DateFilter(val label: String) {
    ANY("Any date"), WEEK("Last 7 days"), MONTH("Last 30 days")
}
private enum class PreviewMode(val label: String) {
    DETAILS("Details"), SMALL("Small"), MEDIUM("Medium"), LARGE("Large")
}

class MainActivity : ComponentActivity() {
    private lateinit var documentPicker: ActivityResultLauncher<Array<String>>
    private lateinit var repository: DocumentRepository
    private val importStatus = mutableStateOf<String?>(null)
    private val searchHistory = mutableStateOf<List<String>>(emptyList())
    private val prefs by lazy { getSharedPreferences("filekhoj_preferences", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DocumentRepository(applicationContext)
        searchHistory.value = prefs.getString("recent_searches", "")
            .orEmpty().split("\n").filter(String::isNotBlank).take(3)

        documentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selectedUris ->
            if (selectedUris.isNullOrEmpty()) return@registerForActivityResult
            selectedUris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // Some providers do not grant persistable permissions; import still proceeds.
                }
            }
            lifecycleScope.launch {
                importStatus.value = "Importing ${selectedUris.size} document(s)…"
                val result = repository.importDocuments(selectedUris)
                importStatus.value = buildString {
                    append("Imported ${result.imported}")
                    if (result.alreadyPresent > 0) append(" • ${result.alreadyPresent} already added")
                    if (result.failed > 0) append(" • ${result.failed} failed")
                }
            }
        }

        setContent {
            FileKhojApp(
                context = applicationContext,
                repository = repository,
                statusMessage = importStatus.value,
                searchHistory = searchHistory.value,
                onSearchSubmitted = ::saveSearch,
                onImportClick = {
                    documentPicker.launch(arrayOf(
                        "application/pdf", "image/*", "text/plain",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                },
                onOpenDocument = ::openDocument,
                onStatus = { importStatus.value = it }
            )
        }
    }

    private fun saveSearch(raw: String) {
        val query = raw.trim()
        if (query.isBlank()) return
        val updated = (listOf(query) + searchHistory.value.filterNot { it.equals(query, true) }).take(3)
        searchHistory.value = updated
        prefs.edit().putString("recent_searches", updated.joinToString("\n")).apply()
    }

    private fun openDocument(document: DocumentEntity) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(document.sourceUri), document.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            importStatus.value = "No app found to open this file type."
        } catch (_: SecurityException) {
            importStatus.value = "File access expired. Remove this entry and import the document again."
        }
    }
}

@Composable
private fun FileKhojApp(
    context: Context,
    repository: DocumentRepository,
    statusMessage: String?,
    searchHistory: List<String>,
    onSearchSubmitted: (String) -> Unit,
    onImportClick: () -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onStatus: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf(emptyList<DocumentEntity>()) }
    var typeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var dateFilter by remember { mutableStateOf(DateFilter.ANY) }
    var previewMode by remember { mutableStateOf(PreviewMode.MEDIUM) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var dateMenuExpanded by remember { mutableStateOf(false) }
    var viewMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository) {
        repository.observeDocuments().collect { incoming ->
            documents = incoming
            selectedIds = selectedIds.intersect(incoming.map { it.id }.toSet())
        }
    }

    val filteredDocuments = remember(query, documents, typeFilter, dateFilter) {
        val now = System.currentTimeMillis()
        documents.filter { document ->
            val matchesQuery = query.isBlank() || document.displayName.contains(query.trim(), true)
            val mime = document.mimeType.lowercase(Locale.ROOT)
            val matchesType = when (typeFilter) {
                TypeFilter.ALL -> true
                TypeFilter.PDF -> mime.contains("pdf")
                TypeFilter.IMAGE -> mime.startsWith("image/")
                TypeFilter.DOCUMENT -> mime.contains("word") || mime.contains("text") ||
                    mime.contains("officedocument") || mime.contains("spreadsheet")
                TypeFilter.OTHER -> !(mime.contains("pdf") || mime.startsWith("image/") ||
                    mime.contains("word") || mime.contains("text") || mime.contains("officedocument") ||
                    mime.contains("spreadsheet"))
            }
            val age = now - document.dateAdded
            val matchesDate = when (dateFilter) {
                DateFilter.ANY -> true
                DateFilter.WEEK -> age <= 7L * 24 * 60 * 60 * 1000
                DateFilter.MONTH -> age <= 30L * 24 * 60 * 60 * 1000
            }
            matchesQuery && matchesType && matchesDate
        }
    }
    val visibleIds = filteredDocuments.map { it.id }.toSet()
    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("शबाना", style = MaterialTheme.typography.titleMedium)
                Text("FileKhoj", style = MaterialTheme.typography.headlineLarge)
                Text("तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!", style = MaterialTheme.typography.titleMedium)
                Text("Save once. Search anything. Find instantly.", style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search filenames…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmitted(query) })
                )
                if (searchHistory.isNotEmpty()) {
                    Text("Recent searches", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        searchHistory.forEach { recent ->
                            FilterChip(
                                selected = query.equals(recent, true),
                                onClick = { query = recent; onSearchSubmitted(recent) },
                                label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        FilterChip(selected = typeFilter != TypeFilter.ALL, onClick = { typeMenuExpanded = true },
                            label = { Text(typeFilter.label) })
                        DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                            TypeFilter.values().forEach { filter ->
                                DropdownMenuItem(text = { Text(filter.label) }, onClick = {
                                    typeFilter = filter; typeMenuExpanded = false
                                })
                            }
                        }
                    }
                    Box {
                        FilterChip(selected = dateFilter != DateFilter.ANY, onClick = { dateMenuExpanded = true },
                            label = { Text(dateFilter.label) })
                        DropdownMenu(expanded = dateMenuExpanded, onDismissRequest = { dateMenuExpanded = false }) {
                            DateFilter.values().forEach { filter ->
                                DropdownMenuItem(text = { Text(filter.label) }, onClick = {
                                    dateFilter = filter; dateMenuExpanded = false
                                })
                            }
                        }
                    }
                    Box {
                        FilterChip(selected = previewMode != PreviewMode.MEDIUM, onClick = { viewMenuExpanded = true },
                            label = { Text("View: ${previewMode.label}") })
                        DropdownMenu(expanded = viewMenuExpanded, onDismissRequest = { viewMenuExpanded = false }) {
                            PreviewMode.values().forEach { mode ->
                                DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                                    previewMode = mode; viewMenuExpanded = false
                                })
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onImportClick, modifier = Modifier.weight(1f)) { Text("＋ Import files") }
                    Text("${filteredDocuments.size} shown / ${documents.size} total",
                        style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = allVisibleSelected, onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + visibleIds else selectedIds - visibleIds
                    })
                    Text(if (allVisibleSelected) "Deselect visible" else "Select all visible",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (selectedIds.isNotEmpty()) {
                        Text("${selectedIds.size} selected", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { showDeleteConfirmation = true }) { Text("Delete") }
                        TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
                    }
                }
                statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                if (filteredDocuments.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (documents.isEmpty()) "Your library is empty. Import documents to get started."
                        else "No documents match the current search and filters.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDocuments, key = { it.id }) { document ->
                            DocumentRow(
                                context = context,
                                document = document,
                                mode = previewMode,
                                selected = document.id in selectedIds,
                                onSelectedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + document.id else selectedIds - document.id
                                },
                                onClick = { onOpenDocument(document) },
                                onDelete = {
                                    scope.launch {
                                        val result = repository.deleteDocuments(listOf(document.id))
                                        onStatus(if (result.deleted > 0) "Removed from FileKhoj. The original file was not deleted."
                                        else "Could not remove this document entry.")
                                    }
                                },
                                onFavourite = {
                                    scope.launch { repository.setFavourite(document.id, !document.isFavourite) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove selected documents?") },
            text = { Text("This removes ${selectedIds.size} item(s) from the FileKhoj library. Original documents selected from your device or cloud provider will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    showDeleteConfirmation = false
                    scope.launch {
                        val result = repository.deleteDocuments(ids)
                        selectedIds = emptySet()
                        onStatus("Removed ${result.deleted} item(s) from FileKhoj" +
                            if (result.failed > 0) "; ${result.failed} could not be removed." else ". Original source files were kept.")
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DocumentRow(
    context: Context,
    document: DocumentEntity,
    mode: PreviewMode,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFavourite: () -> Unit
) {
    val isPdf = document.mimeType.contains("pdf", true)
    val bitmap by produceState<Bitmap?>(initialValue = null, document.sourceUri, mode) {
        value = if (mode == PreviewMode.DETAILS) null else withContext(Dispatchers.IO) {
            loadPreviewBitmap(context, document)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Column(Modifier.weight(1f).clickable(onClick = onClick)) {
                    Text(document.displayName, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (mode == PreviewMode.DETAILS || mode == PreviewMode.MEDIUM || mode == PreviewMode.LARGE) {
                        Text("${document.mimeType} • ${formatSize(document.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onFavourite) { Text(if (document.isFavourite) "★" else "☆") }
            }
            if (mode != PreviewMode.DETAILS) {
                val previewHeight = when (mode) {
                    PreviewMode.SMALL -> 54.dp
                    PreviewMode.MEDIUM -> 110.dp
                    PreviewMode.LARGE -> 190.dp
                    PreviewMode.DETAILS -> 0.dp
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "First-page or file preview of ${document.displayName}",
                        modifier = Modifier.fillMaxWidth().height(previewHeight),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(previewHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isPdf) "PDF • first-page preview unavailable" else "File preview unavailable",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (mode == PreviewMode.DETAILS || mode == PreviewMode.MEDIUM || mode == PreviewMode.LARGE) {
                Text("Added ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(document.dateAdded))}",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (document.isFavourite) "★ Favourite" else "Index: ${document.indexStatus}",
                    style = MaterialTheme.typography.labelSmall)
                val signatureLabel = when (document.signatureStatus) {
                    DocumentEntity.SIGNATURE_STATUS_VERIFIED -> "✓ Digitally signed — verified"
                    DocumentEntity.SIGNATURE_STATUS_UNTRUSTED -> "⚠ Signature found — certificate untrusted"
                    DocumentEntity.SIGNATURE_STATUS_INVALID -> "✕ Signature verification failed"
                    DocumentEntity.SIGNATURE_STATUS_INCOMPLETE -> "Signature verification incomplete"
                    DocumentEntity.SIGNATURE_STATUS_UNSIGNED -> "No digital signature"
                    else -> "Signature not checked"
                }
                Text(signatureLabel, style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDelete) { Text("Remove") }
                TextButton(onClick = onClick) { Text("Open") }
            }
        }
    }
}

private fun loadPreviewBitmap(context: Context, document: DocumentEntity): Bitmap? {
    return try {
        val uri = Uri.parse(document.sourceUri)
        if (document.mimeType.startsWith("image/")) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, android.util.Size(900, 900), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } else if (document.mimeType.contains("pdf", true)) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) null else renderer.openPage(0).use { page ->
                        val width = 900
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes < 1024L -> "${sizeBytes} B"
    sizeBytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", sizeBytes / (1024.0 * 1024.0))
}
