package com.shabana.filekhoj

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shabana.filekhoj.data.DocumentEntity
import com.shabana.filekhoj.data.DocumentRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var documentPicker: ActivityResultLauncher<Array<String>>
    private lateinit var repository: DocumentRepository
    private val importStatus = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DocumentRepository(applicationContext)

        documentPicker = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { selectedUris ->
            if (selectedUris.isNullOrEmpty()) return@registerForActivityResult

            selectedUris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some document providers do not offer persistable URI grants.
                    // The import is still attempted; the user may need to reselect later.
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
                repository = repository,
                statusMessage = importStatus.value,
                onImportClick = {
                    documentPicker.launch(
                        arrayOf(
                            "application/pdf",
                            "image/*",
                            "text/plain",
                            "application/msword",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        )
                    )
                },
                onOpenDocument = ::openDocument
            )
        }
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
            importStatus.value = "File access expired. Import this document again."
        }
    }
}

@Composable
private fun FileKhojApp(
    repository: DocumentRepository,
    statusMessage: String?,
    onImportClick: () -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf(emptyList<DocumentEntity>()) }

    LaunchedEffect(repository) {
        repository.observeDocuments().collect { documents = it }
    }

    val filteredDocuments = remember(query, documents) {
        if (query.isBlank()) documents
        else documents.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("शबाना", style = MaterialTheme.typography.titleLarge)
                Text("FileKhoj", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!",
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Save once. Search anything. Find instantly.")

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search filenames…") },
                    singleLine = true
                )

                Button(
                    onClick = onImportClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("＋  Import documents")
                }

                statusMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Your documents", style = MaterialTheme.typography.titleMedium)
                    Text("${documents.size} saved", style = MaterialTheme.typography.bodyMedium)
                }

                if (filteredDocuments.isEmpty()) {
                    Text(
                        if (documents.isEmpty()) {
                            "Your library is empty. Import a PDF, photo, or document to get started."
                        } else {
                            "No filenames match “${query.trim()}”."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredDocuments, key = { it.id }) { document ->
                            DocumentRow(document = document, onClick = { onOpenDocument(document) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(document: DocumentEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                document.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${document.mimeType} • ${formatSize(document.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Added ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(document.dateAdded))}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Not indexed yet", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes < 1024L -> "${sizeBytes} B"
    sizeBytes < 1024L * 1024L -> "${sizeBytes / 1024.0} KB".formatSizeDecimal()
    else -> "${sizeBytes / (1024.0 * 1024.0)} MB".formatSizeDecimal()
}

private fun String.formatSizeDecimal(): String {
    val number = toDoubleOrNull() ?: return this
    return String.format(java.util.Locale.getDefault(), "%.1f", number) +
        if (endsWith(" KB")) " KB" else " MB"
}
