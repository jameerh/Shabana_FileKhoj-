package com.shabana.filekhoj

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.shabana.filekhoj.data.BackupRestoreManager
import com.shabana.filekhoj.data.ScheduledBackupWorker
import com.shabana.filekhoj.data.DocumentEntity
import com.shabana.filekhoj.data.DocumentRepository
import com.shabana.filekhoj.pdf.PdfEditorScreen
import java.io.File
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
private enum class SignatureFilter(val label: String) {
    ANY("Any signature"), VERIFIED("Verified"), NEEDS_REVIEW("Needs review"), UNSIGNED("Unsigned")
}
private enum class PreviewMode(val label: String) {
    DETAILS("Details"), SMALL("Small"), MEDIUM("Medium"), LARGE("Large")
}

class MainActivity : ComponentActivity() {
    private lateinit var documentPicker: ActivityResultLauncher<Array<String>>
    private lateinit var pdfExportLauncher: ActivityResultLauncher<String>
    private lateinit var exportFolderLauncher: ActivityResultLauncher<Uri?>
    private lateinit var backupExportLauncher: ActivityResultLauncher<String>
    private lateinit var backupRestoreLauncher: ActivityResultLauncher<Array<String>>
    private var pendingBackupFile: File? = null
    private var pendingPdfExportFile: File? = null
    private var pendingBulkExportDocuments: List<DocumentEntity> = emptyList()
    private var pendingPdfExportCompletion: ((Boolean, String?) -> Unit)? = null
    private lateinit var repository: DocumentRepository
    private val importStatus = mutableStateOf<String?>(null)
    private val searchHistory = mutableStateOf<List<String>>(emptyList())
    private val passwordQueue = mutableStateOf<List<Uri>>(emptyList())
    private val passwordError = mutableStateOf<String?>(null)
    private val prefs by lazy { getSharedPreferences("filekhoj_preferences", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = DocumentRepository(applicationContext)
        val savedBackupInterval = getSharedPreferences(ScheduledBackupWorker.PREFS, Context.MODE_PRIVATE).getLong(ScheduledBackupWorker.KEY_INTERVAL_DAYS, 0L).toInt()
        if (savedBackupInterval in setOf(1, 7, 30)) {
            ScheduledBackupWorker.schedule(applicationContext, savedBackupInterval)
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("filekhoj-backup-on-launch", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScheduledBackupWorker>().build())
        }
        searchHistory.value = prefs.getString("recent_searches", "")
            .orEmpty().split("\n").filter(String::isNotBlank).take(3)

        backupExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destinationUri ->
            val backup = pendingBackupFile
            pendingBackupFile = null
            if (destinationUri == null || backup == null) importStatus.value = "Backup export cancelled."
            else lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                            backup.inputStream().use { it.copyTo(output) }
                        } ?: throw IllegalStateException("Could not write to destination.")
                    }
                    importStatus.value = "Backup exported successfully."
                } catch (error: Exception) { importStatus.value = "Backup export failed: ${error.message}" }
                finally { backup.delete() }
            }
        }
        backupRestoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { sourceUri ->
            if (sourceUri == null) importStatus.value = "Restore cancelled."
            else lifecycleScope.launch {
                importStatus.value = "Restoring backup…"
                val temp = File(cacheDir, "filekhoj-restore-${System.currentTimeMillis()}.zip")
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(sourceUri)?.use { input ->
                            temp.outputStream().buffered().use { input.copyTo(it) }
                        } ?: throw IllegalStateException("Could not read backup.")
                    }
                    val result = withContext(Dispatchers.IO) { BackupRestoreManager(applicationContext).restoreBackup(temp) }
                    importStatus.value = "Restore complete: ${result.restored} restored, ${result.skipped} skipped."
                } catch (error: Exception) { importStatus.value = "Restore failed: ${error.message}. Existing library kept." }
                finally { temp.delete() }
            }
        }

        exportFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            val selected = pendingBulkExportDocuments
            pendingBulkExportDocuments = emptyList()
            if (treeUri == null || selected.isEmpty()) {
                if (treeUri == null) importStatus.value = "Folder selection cancelled. No files were changed."
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* Some providers grant temporary access only. */ }
            lifecycleScope.launch {
                var copied = 0
                val failures = mutableListOf<String>()
                selected.forEach { document ->
                    try {
                        withContext(Dispatchers.IO) {
                            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
                            val destination = DocumentsContract.createDocument(
                                contentResolver, rootUri, document.mimeType.ifBlank { "application/octet-stream" },
                                uniqueExportName(treeUri, document.displayName)
                            ) ?: throw IllegalStateException("Destination provider refused to create the file")
                            val input = if (document.isStoredDecrypted) {
                                val file = document.managedPath?.let(::File)
                                    ?: throw IllegalStateException("Private unlocked copy is missing")
                                if (!file.isFile) throw IllegalStateException("Private unlocked copy is missing")
                                file.inputStream()
                            } else {
                                contentResolver.openInputStream(Uri.parse(document.sourceUri))
                                    ?: throw IllegalStateException("Source file is no longer accessible")
                            }
                            try {
                                val output = contentResolver.openOutputStream(destination, "w")
                                    ?: throw IllegalStateException("Could not open destination file")
                                input.use { source -> output.use { target -> source.copyTo(target) } }
                            } catch (error: Exception) {
                                try { DocumentsContract.deleteDocument(contentResolver, destination) } catch (_: Exception) { }
                                throw error
                            }
                        }
                        copied++
                    } catch (error: Exception) {
                        failures += "${document.displayName}: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
                val message = buildString {
                    append("Saved $copied of ${selected.size} selected file(s) to the chosen folder.")
                    if (failures.isNotEmpty()) append(" Failed: ${failures.take(3).joinToString("; ")}")
                }
                importStatus.value = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

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
                passwordQueue.value = result.passwordRequiredUris
                passwordError.value = null
                importStatus.value = buildString {
                    append("Imported ${result.imported}")
                    if (result.alreadyPresent > 0) append(" • ${result.alreadyPresent} already added")
                    if (result.failed > 0) append(" • ${result.failed} failed")
                    if (result.passwordRequiredUris.isNotEmpty()) {
                        append(" • password required for ${result.passwordRequiredUris.size} PDF(s)")
                    }
                }
            }
        }



        pdfExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { destinationUri ->
            val exportFile = pendingPdfExportFile
            val completion = pendingPdfExportCompletion
            pendingPdfExportFile = null
            pendingPdfExportCompletion = null

            if (destinationUri == null) {
                exportFile?.delete()
                val detail = "Save was cancelled. Your PDF edits are still open."
                importStatus.value = detail
                completion?.invoke(false, detail)
                return@registerForActivityResult
            }

            if (exportFile == null || !exportFile.isFile || exportFile.length() <= 5L) {
                exportFile?.delete()
                val detail = "The edited PDF file is missing or empty. Please try Save again."
                importStatus.value = detail
                completion?.invoke(false, detail)
                return@registerForActivityResult
            }

            try {
                contentResolver.takePersistableUriPermission(
                    destinationUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers grant only temporary URI permissions. The immediate save can still proceed.
            }

            lifecycleScope.launch {
                try {
                    importStatus.value = "Saving edited PDF to the selected location…"
                    val resultMessage = withContext(Dispatchers.IO) {
                        val destination = contentResolver.openOutputStream(destinationUri, "w")
                            ?: throw IllegalStateException("Android could not open the selected save location.")
                        var copiedBytes = 0L
                        destination.use { output ->
                            exportFile.inputStream().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    copiedBytes += count
                                }
                            }
                            output.flush()
                        }
                        check(copiedBytes > 5L) { "The selected PDF file was written as an empty file." }
                        val header = ByteArray(5)
                        exportFile.inputStream().use { input ->
                            check(input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") {
                                "The generated file is not a valid PDF."
                            }
                        }
                        copiedBytes
                    }

                    val importResult = repository.importDocuments(listOf(destinationUri))
                    val message = when {
                        importResult.imported > 0 ->
                            "PDF saved ($resultMessage bytes) and added to your FileKhoj library."
                        importResult.alreadyPresent > 0 ->
                            "PDF saved ($resultMessage bytes). It is already in your FileKhoj library."
                        importResult.failed > 0 ->
                            "PDF saved ($resultMessage bytes), but FileKhoj could not index the saved copy."
                        else ->
                            "PDF saved successfully ($resultMessage bytes)."
                    }
                    importStatus.value = message
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    completion?.invoke(true, message)
                } catch (error: Exception) {
                    val detail = error.message ?: error.javaClass.simpleName
                    val message = "PDF save failed: $detail"
                    Log.e("ShabanaFileKhoj", "Save As destination write/import failed", error)
                    importStatus.value = message
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    completion?.invoke(false, message)
                } finally {
                    exportFile.delete()
                }
            }
        }

        setContent {
            FileKhojApp(
                context = this@MainActivity,
                repository = repository,
                statusMessage = importStatus.value,
                searchHistory = searchHistory.value,
                passwordRequest = passwordQueue.value.firstOrNull(),
                passwordError = passwordError.value,
                onPasswordSubmit = { uri, password ->
                    lifecycleScope.launch {
                        val result = repository.importDocuments(listOf(uri), mapOf(uri.toString() to password))
                        if (result.passwordRequiredUris.isNotEmpty()) {
                            passwordError.value = "Incorrect password or unsupported PDF encryption. Try again."
                        } else {
                            passwordQueue.value = passwordQueue.value.drop(1)
                            passwordError.value = null
                            importStatus.value = if (result.imported > 0) {
                                "PDF unlocked and saved in private FileKhoj storage. Password was not saved."
                            } else if (result.failed > 0) {
                                "Could not import this PDF. The source file was kept unchanged."
                            } else {
                                "PDF was already in your library."
                            }
                        }
                    }
                },
                onPasswordCancel = {
                    passwordQueue.value = passwordQueue.value.drop(1)
                    passwordError.value = null
                    importStatus.value = "Password-protected PDF skipped. Original file was not changed."
                },
                onSearchSubmitted = ::saveSearch,
                onBackupClick = ::createBackupAndExport,
                onRestoreClick = {
                    importStatus.value = "Choose a FileKhoj backup ZIP to restore…"
                    backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
                onBackupSchedule = { days ->
                    if (days == null) {
                        ScheduledBackupWorker.disable(applicationContext)
                        importStatus.value = "Automatic local backup disabled. Existing backup files were kept."
                    } else {
                        ScheduledBackupWorker.schedule(applicationContext, days)
                        importStatus.value = "Schedule saved: automatic local backup every ${days} day(s). Google Drive upload is not enabled yet."
                    }
                },
                onImportClick = {
                    documentPicker.launch(arrayOf(
                        "application/pdf", "image/*", "text/plain",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    ))
                },
                onOpenDocument = ::openDocument,
                onPdfSaveRequest = ::launchPdfExport,
                onSaveSelected = { selected ->
                    if (selected.isEmpty()) importStatus.value = "Select at least one file to save."
                    else {
                        pendingBulkExportDocuments = selected
                        try { exportFolderLauncher.launch(null) }
                        catch (error: Exception) {
                            pendingBulkExportDocuments = emptyList()
                            importStatus.value = "Could not open folder picker: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                },
                onStatus = { importStatus.value = it }
            )
        }
    }

    private fun createBackupAndExport() {
        lifecycleScope.launch {
            val temp = File(cacheDir, "filekhoj-backup-${System.currentTimeMillis()}.zip")
            try {
                importStatus.value = "Preparing document backup…"
                val result = withContext(Dispatchers.IO) { BackupRestoreManager(applicationContext).createBackup(temp) }
                pendingBackupFile = temp
                importStatus.value = "Prepared backup of ${result.documentCount} documents."
                backupExportLauncher.launch("Shabana-FileKhoj-backup.zip")
            } catch (error: Exception) {
                temp.delete()
                importStatus.value = "Backup failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun launchPdfExport(
        file: File,
        suggestedName: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (pendingPdfExportFile != null) {
            onComplete(false, "A PDF Save As operation is already open.")
            Toast.makeText(this, "Finish the current PDF save first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!file.isFile || file.length() <= 5L) {
            onComplete(false, "The generated PDF is missing or empty.")
            return
        }

        pendingPdfExportFile = file
        pendingPdfExportCompletion = onComplete
        try {
            Toast.makeText(this, "PDF generated. Choose a folder and tap Save.", Toast.LENGTH_LONG).show()
            pdfExportLauncher.launch(suggestedName)
        } catch (error: Exception) {
            pendingPdfExportFile = null
            pendingPdfExportCompletion = null
            file.delete()
            val detail = error.message ?: error.javaClass.simpleName
            Log.e("ShabanaFileKhoj", "Could not launch Android CreateDocument picker", error)
            val message = "Could not open Android Save As: $detail"
            importStatus.value = message
            onComplete(false, message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun uniqueExportName(treeUri: Uri, requestedName: String): String {
        val safeName = requestedName.trim().ifBlank { "FileKhoj document" }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val existing = mutableSetOf<String>()
        contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0) while (cursor.moveToNext()) cursor.getString(index)?.let(existing::add)
            }
        if (safeName !in existing) return safeName
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        var suffix = 2
        while ("$base (FileKhoj $suffix)$extension" in existing) suffix++
        return "$base (FileKhoj $suffix)$extension"
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
            val documentUri = if (document.isStoredDecrypted) {
                val managedFile = document.managedPath?.let(::File)
                if (managedFile == null || !managedFile.isFile) {
                    importStatus.value = "Unlocked local copy is missing. Remove this entry and import the protected PDF again."
                    return
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", managedFile)
            } else {
                Uri.parse(document.sourceUri)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(documentUri, document.mimeType)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileKhojApp(
    context: Context,
    repository: DocumentRepository,
    statusMessage: String?,
    searchHistory: List<String>,
    passwordRequest: Uri?,
    passwordError: String?,
    onPasswordSubmit: (Uri, String) -> Unit,
    onPasswordCancel: () -> Unit,
    onSearchSubmitted: (String) -> Unit,
    onImportClick: () -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onPdfSaveRequest: (File, String, (Boolean, String?) -> Unit) -> Unit,
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onBackupSchedule: (Int?) -> Unit,
    onSaveSelected: (List<DocumentEntity>) -> Unit,
    onStatus: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showFiltersDialog by remember { mutableStateOf(false) }
    var draftTypeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var draftDateFilter by remember { mutableStateOf(DateFilter.ANY) }
    var draftSignatureFilter by remember { mutableStateOf(SignatureFilter.ANY) }
    var draftCategory by remember { mutableStateOf("All categories") }
    var draftFavouritesOnly by remember { mutableStateOf(false) }
    var draftPreviewMode by remember { mutableStateOf(PreviewMode.MEDIUM) }
    var documents by remember { mutableStateOf(emptyList<DocumentEntity>()) }
    var fullTextMatchIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var fullTextMatchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var dateFilter by remember { mutableStateOf(DateFilter.ANY) }
    var signatureFilter by remember { mutableStateOf(SignatureFilter.ANY) }
    var favouritesOnly by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("All categories") }
    var previewMode by remember { mutableStateOf(PreviewMode.MEDIUM) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editingDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var editingPdfDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var editName by remember(editingDocument) { mutableStateOf(editingDocument?.displayName.orEmpty()) }
    var editCategory by remember(editingDocument) { mutableStateOf(editingDocument?.category.orEmpty()) }
    var passwordText by remember(passwordRequest) { mutableStateOf("") }
    var typeMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    var signatureMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showFinalDeleteConfirmation by remember { mutableStateOf(false) }
    var customizingDocument by remember { mutableStateOf<DocumentEntity?>(null) }
    var customLabelDraft by remember(customizingDocument) { mutableStateOf("") }
    var customColorDraft by remember(customizingDocument) { mutableStateOf(0xFFEAF2EC.toInt()) }
    val cardPrefs = remember(context) { context.getSharedPreferences("filekhoj_card_metadata", Context.MODE_PRIVATE) }
    var cardMetadataVersion by remember { mutableStateOf(0) }
    var showBrandHero by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val profilesStore = remember(context) { UserRegistrationStore(context) }
    var profiles by remember { mutableStateOf(profilesStore.profiles()) }
    var activeProfile by remember { mutableStateOf(profilesStore.activeProfile()) }
    var showRegistration by remember { mutableStateOf(activeProfile == null) }
    var showProfile by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    var showAdminContact by remember { mutableStateOf(false) }
    var adminNumberDraft by remember { mutableStateOf(profilesStore.adminWhatsappNumber()) }
    var nameInput by remember { mutableStateOf("") }
    var mobileInput by remember { mutableStateOf("") }
    var locationInput by remember { mutableStateOf("") }
    var registrationError by remember { mutableStateOf<String?>(null) }
    var registerAsAdmin by remember { mutableStateOf(false) }
    var pendingProfile by remember {
        mutableStateOf(activeProfile?.takeIf { !it.notificationConfirmed })
    }
    var showPendingNotification by remember { mutableStateOf(pendingProfile != null) }
    var whatsappOpened by remember { mutableStateOf(false) }
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(repository) {
        repository.observeDocuments().collect { incoming ->
            documents = incoming
            selectedIds = selectedIds.intersect(incoming.map { it.id }.toSet())
        }
    }
    LaunchedEffect(query, documents, repository) {
        fullTextMatchQuery = query
        fullTextMatchIds = emptySet()
        if (query.isNotBlank()) {
            kotlinx.coroutines.delay(160)
            fullTextMatchIds = repository.searchDocuments(query).map { it.id }.toSet()
        }
    }

    val categoryOptions = remember(documents) {
        listOf("All categories") + documents.mapNotNull { it.category?.trim()?.takeIf(String::isNotBlank) }
            .distinct().sorted()
    }
    val filteredDocuments = remember(
        query, documents, typeFilter, dateFilter, signatureFilter, favouritesOnly,
        selectedCategory, fullTextMatchIds, fullTextMatchQuery
    ) {
        val now = System.currentTimeMillis()
        documents.filter { document ->
            val mime = document.mimeType.lowercase(Locale.ROOT)
            val matchesQuery = query.isBlank() || document.displayName.contains(query.trim(), true) ||
                (fullTextMatchQuery == query && document.id in fullTextMatchIds)
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
            val matchesSignature = when (signatureFilter) {
                SignatureFilter.ANY -> true
                SignatureFilter.VERIFIED -> document.signatureStatus == DocumentEntity.SIGNATURE_STATUS_VERIFIED
                SignatureFilter.UNSIGNED -> document.signatureStatus == DocumentEntity.SIGNATURE_STATUS_UNSIGNED
                SignatureFilter.NEEDS_REVIEW -> document.signatureStatus in setOf(
                    DocumentEntity.SIGNATURE_STATUS_UNKNOWN, DocumentEntity.SIGNATURE_STATUS_UNTRUSTED,
                    DocumentEntity.SIGNATURE_STATUS_INVALID, DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
                )
            }
            matchesQuery && matchesType && matchesDate &&
                (!favouritesOnly || document.isFavourite) &&
                (selectedCategory == "All categories" || document.category == selectedCategory) &&
                matchesSignature
        }
    }
    val visibleIds = remember(filteredDocuments) { filteredDocuments.map { it.id }.toSet() }
    LaunchedEffect(visibleIds) { selectedIds = selectedIds.intersect(visibleIds) }
    val visibleSelectedIds = selectedIds.intersect(visibleIds)
    val allVisibleSelected = visibleIds.isNotEmpty() && visibleSelectedIds.containsAll(visibleIds)

    fun createRegistrationMessage(profile: LocalUserProfile): String {
        val date = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()
        ).format(Date(profile.registeredAt))
        return """
            New user registration — Shabana FileKhoj

            Name: ${profile.name}
            Mobile: ${profile.mobile}
            Location: ${profile.location}
            App: Shabana FileKhoj
            Registration date: $date
        """.trimIndent()
    }

    fun openRegistrationWhatsApp(profile: LocalUserProfile) {
        val destination = profilesStore.adminWhatsappNumber()
        if (destination.isBlank()) {
            onStatus("Admin WhatsApp number is not configured. Set it in Profile → Admin contact.")
            adminNumberDraft = ""
            showAdminContact = true
            return
        }
        val url = "https://wa.me/$destination?text=${Uri.encode(createRegistrationMessage(profile))}"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            whatsappOpened = true
        } catch (_: Exception) {
            whatsappOpened = false
            onStatus("Could not open WhatsApp. Install or enable WhatsApp, then try again.")
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF07583D), onPrimary = Color.White,
            secondary = Color(0xFFD6A83B), background = Color(0xFFF7F5EF),
            surface = Color.White, surfaceVariant = Color(0xFFEDEFEA),
            onSurface = Color(0xFF17251F)
        )
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Color(0xFFF7F5EF)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("शबाना", color = Color(0xFFB88A24), fontWeight = FontWeight.Bold)
                        Text("FileKhoj", style = MaterialTheme.typography.headlineSmall,
                            color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                        Text("Save once. Search anything.", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.HorizontalDivider()
                    NavigationDrawerItem(label = { Text("⌂  Home") }, selected = true, onClick = {
                        query = ""
                        typeFilter = TypeFilter.ALL
                        dateFilter = DateFilter.ANY
                        signatureFilter = SignatureFilter.ANY
                        selectedCategory = "All categories"
                        favouritesOnly = false
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("▤  All documents") }, selected = false, onClick = {
                        query = ""
                        favouritesOnly = false
                        typeFilter = TypeFilter.ALL
                        dateFilter = DateFilter.ANY
                        signatureFilter = SignatureFilter.ANY
                        selectedCategory = "All categories"
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("★  Favourites") }, selected = false, onClick = {
                        favouritesOnly = true
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("＋  Import documents") }, selected = false, onClick = {
                        scope.launch { drawerState.close() }
                        onImportClick()
                    })
                    NavigationDrawerItem(label = { Text("☁  Backup & Restore") }, selected = false, onClick = {
                        showBackupDialog = true
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("♙  Profile / Login") }, selected = false, onClick = {
                        showProfile = true
                        scope.launch { drawerState.close() }
                    })
                    if (activeProfile?.role == UserRegistrationStore.ROLE_ADMIN) {
                        NavigationDrawerItem(label = { Text("⚙  Admin panel") }, selected = false, onClick = {
                            showAdmin = true
                            scope.launch { drawerState.close() }
                        })
                    }
                    NavigationDrawerItem(label = { Text("☎  Admin WhatsApp contact") }, selected = false, onClick = {
                        adminNumberDraft = profilesStore.adminWhatsappNumber()
                        showAdminContact = true
                        scope.launch { drawerState.close() }
                    })
                    Text("PRIVATE • OFFLINE-FIRST", modifier = Modifier.padding(18.dp),
                        color = Color(0xFF668173), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xFFF7F5EF))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰", fontSize = 24.sp, color = Color(0xFF07583D))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("शबाना FileKhoj", style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showBrandHero = !showBrandHero }) {
                        Text(if (showBrandHero) "⌃" else "⌄", fontSize = 22.sp,
                            color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showProfile = true }) {
                        Text(activeProfile?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "♙",
                            fontSize = 22.sp, color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (showBrandHero) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF064B33))
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("शबाना FileKhoj", color = Color(0xFFFFD76B),
                                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                        Text("तुमचे सर्व दस्तऐवज, शोधा क्षणार्धात!",
                                            color = Color.White, style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold)
                                        Text("Save once. Search anything. Find instantly.",
                                            color = Color(0xFFD8EBDD), style = MaterialTheme.typography.bodySmall)
                                        Text("PDF • फोटो • कागदपत्रे • OCR शोध",
                                            color = Color(0xFFFFD76B), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF0B6847)),
                                        contentAlignment = Alignment.Center) {
                                        Text("⌕", color = Color(0xFFFFD76B), fontSize = 40.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("काय शोधायचे आहे?", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold, color = Color(0xFF183B2D))
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it },
                                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                                    placeholder = { Text("नाव, क्रमांक किंवा PDF मधील शब्द शोधा…") },
                                    leadingIcon = { Text("⌕", fontSize = 24.sp) },
                                    trailingIcon = {
                                        if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("Clear") }
                                    },
                                    singleLine = true, shape = RoundedCornerShape(14.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmitted(query) })
                                )
                            }
                        }
                    }
                    if (searchHistory.isNotEmpty() && (searchFocused || query.isNotBlank())) {
                        item {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text("Recent searches", style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF64756C), fontWeight = FontWeight.SemiBold)
                                    searchHistory.take(3).forEach { recent ->
                                        Row(Modifier.fillMaxWidth().clickable {
                                            query = recent
                                            searchFocused = false
                                            onSearchSubmitted(recent)
                                        }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("◷", color = Color(0xFF64756C), modifier = Modifier.padding(end = 8.dp))
                                            Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = typeFilter != TypeFilter.ALL || dateFilter != DateFilter.ANY ||
                                    signatureFilter != SignatureFilter.ANY || selectedCategory != "All categories" ||
                                    favouritesOnly || previewMode != PreviewMode.MEDIUM,
                                onClick = {
                                    draftTypeFilter = typeFilter
                                    draftDateFilter = dateFilter
                                    draftSignatureFilter = signatureFilter
                                    draftCategory = selectedCategory
                                    draftFavouritesOnly = favouritesOnly
                                    draftPreviewMode = previewMode
                                    showFiltersDialog = true
                                },
                                label = { Text("☷ Filters") }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${filteredDocuments.size} files", style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF64756C))
                            Spacer(Modifier.weight(1f))
                            if (typeFilter != TypeFilter.ALL || dateFilter != DateFilter.ANY ||
                                signatureFilter != SignatureFilter.ANY || selectedCategory != "All categories" ||
                                favouritesOnly || previewMode != PreviewMode.MEDIUM) {
                                TextButton(onClick = {
                                    typeFilter = TypeFilter.ALL
                                    dateFilter = DateFilter.ANY
                                    signatureFilter = SignatureFilter.ANY
                                    selectedCategory = "All categories"
                                    favouritesOnly = false
                                    previewMode = PreviewMode.MEDIUM
                                }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 1.dp)) {
                                    Text("Reset", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Your library", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${filteredDocuments.size} shown • ${documents.size} saved",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF64756C))
                        }
                    }
                    if (visibleIds.isNotEmpty()) {
                        item {
                            Card(shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = allVisibleSelected, onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + visibleIds else selectedIds - visibleIds
                                    })
                                    Text(if (allVisibleSelected) "Deselect visible" else "Select all visible",
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (visibleSelectedIds.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("${visibleSelectedIds.size} selected", style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                                    }               }
                                }
                            }
                        }
                    if (visibleSelectedIds.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF2EC))
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = {
                                            val selectedDocuments = documents.filter { it.id in visibleSelectedIds }
                                            try {
                                                val shareUris = ArrayList<Uri>()
                                                selectedDocuments.forEach { document ->
                                                    val uri = if (document.isStoredDecrypted) {
                                                        val managedFile = document.managedPath?.let(::File)
                                                        if (managedFile == null || !managedFile.isFile)
                                                            throw IllegalStateException("Unlocked copy missing: ${document.displayName}")
                                                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", managedFile)
                                                    } else Uri.parse(document.sourceUri)
                                                    shareUris.add(uri)
                                                }
                                                if (shareUris.isEmpty()) onStatus("Select at least one file to share.")
                                                else {
                                                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                                        type = "*/*"
                                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                                                        val clip = ClipData.newUri(context.contentResolver, "Selected files", shareUris.first())
                                                        shareUris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                                                        clipData = clip
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share selected files"))
                                                    onStatus("Choose an app to share ${shareUris.size} selected file(s).")
                                                }
                                            } catch (error: Exception) {
                                                onStatus("Could not share selected files: ${error.message ?: error.javaClass.simpleName}")
                                            }
                                        }) { Text("↗ Share") }
                                        TextButton(onClick = {
                                            onSaveSelected(documents.filter { it.id in visibleSelectedIds })
                                        }) { Text("⇩ Save to folder") }
                                        TextButton(onClick = {
                                            val selected = documents.filter { it.id in visibleSelectedIds }
                                            scope.launch {
                                                selected.forEach { repository.setFavourite(it.id, !it.isFavourite) }
                                                onStatus("Updated favourites for ${selected.size} selected file(s).")
                                            }
                                        }) { Text("★ Favourite") }
                                        TextButton(onClick = {
                                            val selected = documents.filter { it.id in visibleSelectedIds }
                                            if (selected.size == 1) {
                                                editingDocument = selected.first()
                                            } else {
                                                onStatus("Select one file to edit its name or category.")
                                            }
                                        }) { Text("✎ Edit") }
                                    }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { showDeleteConfirmation = true }) { Text("− Remove") }
                                        TextButton(onClick = { selectedIds = emptySet() }) { Text("× Clear selection") }
                                    }
                                }
                            }
                        }
                    }
                    statusMessage?.let { message ->
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE7EFE8)),
                                shape = RoundedCornerShape(14.dp)) {
                                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF244A36))
                            }
                        }
                    }
                    if (filteredDocuments.isEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (documents.isEmpty()) "📂" else "⌕", fontSize = 42.sp)
                                    Text(if (documents.isEmpty()) "तुमची डिजिटल फाईल पेटी तयार करा"
                                        else "No matching documents found",
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(if (documents.isEmpty())
                                        "आधार, प्रमाणपत्रे, PDF आणि फोटो एकदाच जोडा. पुढे नाव किंवा मजकूर शोधून लगेच उघडा."
                                    else "Try another keyword or reset the filters.",
                                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF64756C))
                                    if (documents.isEmpty()) {
                                        Button(onClick = onImportClick,
                                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF07583D))) { Text("पहिली फाईल जोडा") }
                                    } else {
                                        TextButton(onClick = {
                                            query = ""; typeFilter = TypeFilter.ALL; dateFilter = DateFilter.ANY
                                            signatureFilter = SignatureFilter.ANY; favouritesOnly = false
                                            selectedCategory = "All categories"
                                        }) { Text("Clear search and filters") }
                                    }
                                }
                            }
                        }
                    } else {
                        items(filteredDocuments, key = { it.id }) { document ->
                            DocumentRow(
                                context = context, document = document, mode = previewMode,
                                selected = document.id in selectedIds,
                                onSelectedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + document.id else selectedIds - document.id
                                },
                                onClick = { onOpenDocument(document) },
                                onEdit = { editingDocument = document },
                                onEditPdf = { editingPdfDocument = document },
                                onDelete = { selectedIds = setOf(document.id); showDeleteConfirmation = true },
                                onFavourite = {
                                    scope.launch { repository.setFavourite(document.id, !document.isFavourite) }
                                },
                                onCustomize = {
                                    customLabelDraft = cardPrefs.getString("labels_${document.id}", "").orEmpty()
                                        .split("\u001F").filter(String::isNotBlank).joinToString(", ")
                                    customColorDraft = cardPrefs.getInt("color_${document.id}", 0xFFFFFFFF.toInt())
                                    customizingDocument = document
                                },
                                customColor = cardPrefs.getInt("color_${document.id}", 0xFFFFFFFF.toInt()),
                                customLabels = cardPrefs.getString("labels_${document.id}", "").orEmpty()
                                    .split("\u001F").filter(String::isNotBlank)
                            )
                        }
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    FloatingActionButton(
                        onClick = { showImportMenu = true },
                        containerColor = Color(0xFF07583D),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) { Text("+", fontSize = 30.sp) }
                    DropdownMenu(expanded = showImportMenu, onDismissRequest = { showImportMenu = false }) {
                        DropdownMenuItem(text = { Text("Import files") },
                            onClick = { showImportMenu = false; onImportClick() },
                            leadingIcon = { Text("＋") })
                    }
                }
                }
            }
        }
    }

    editingPdfDocument?.let { selectedPdf ->
        PdfEditorScreen(
            context = context,
            document = selectedPdf,
            onClose = { editingPdfDocument = null },
            onSaveCopy = onPdfSaveRequest
        )
    }

    if (showRegistration) {
        AlertDialog(
            onDismissRequest = { if (activeProfile != null) showRegistration = false },
            title = { Text(if (activeProfile == null) "Welcome to Shabana FileKhoj" else "Register a profile") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("फक्त आवश्यक माहिती भरा. App name आणि registration date आपोआप जोडली जातील.",
                        style = MaterialTheme.typography.bodySmall)
                    if (profiles.none { it.role == UserRegistrationStore.ROLE_ADMIN }) {
                        Text("Set up this phone as an administrator, or register this phone as a user.",
                            style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !registerAsAdmin,
                                onClick = { registerAsAdmin = false }, label = { Text("User") })
                            FilterChip(selected = registerAsAdmin,
                                onClick = { registerAsAdmin = true }, label = { Text("Administrator") })
                        }
                        if (!registerAsAdmin) {
                            OutlinedTextField(adminNumberDraft, { adminNumberDraft = it },
                                Modifier.fillMaxWidth(), label = { Text("Admin WhatsApp number") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
                            Text("This is an app setup contact, not part of your profile. It is required to notify the administrator.",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF64756C))
                        }
                    }
                    OutlinedTextField(nameInput, { nameInput = it }, Modifier.fillMaxWidth(),
                        label = { Text("Full name") }, singleLine = true)
                    OutlinedTextField(mobileInput, { mobileInput = it }, Modifier.fillMaxWidth(),
                        label = { Text("Mobile number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
                    OutlinedTextField(locationInput, { locationInput = it }, Modifier.fillMaxWidth(),
                        label = { Text("Village / Town / City") }, singleLine = true)
                    Text("No Aadhaar, email, date of birth or document details are requested. Profiles stay on this device.",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF64756C))
                    registrationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        if (!registerAsAdmin && profiles.none { it.role == UserRegistrationStore.ROLE_ADMIN }) {
                            profilesStore.setAdminWhatsappNumber(adminNumberDraft)
                        }
                        val profile = profilesStore.register(
                            nameInput, mobileInput, locationInput, registerAsAdmin
                        )
                        profiles = profilesStore.profiles()
                        activeProfile = profile
                        showRegistration = false
                        registrationError = null
                        if (!profile.notificationConfirmed) {
                            pendingProfile = profile
                            showPendingNotification = true
                            whatsappOpened = false
                            openRegistrationWhatsApp(profile)
                        } else {
                            onStatus("Administrator profile created on this device. Share the configured admin WhatsApp contact with users installing on their own phones.")
                        }
                    } catch (error: IllegalArgumentException) {
                        registrationError = error.message ?: "Please check the details and try again."
                    }
                }) { Text("Register") }
            },
            dismissButton = {
                if (activeProfile != null) TextButton(onClick = { showRegistration = false }) { Text("Cancel") }
            }
        )
    }

    if (showProfile) {
        AlertDialog(
            onDismissRequest = { showProfile = false },
            title = { Text("Profile / Login") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    activeProfile?.let { profile ->
                        Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${profile.mobile}  •  ${profile.location}", style = MaterialTheme.typography.bodySmall)
                        Text(if (profile.role == UserRegistrationStore.ROLE_ADMIN) "Device administrator" else "Local user",
                            color = Color(0xFF07583D), style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Profiles are stored on this phone. This is local profile switching, not cloud authentication.",
                        style = MaterialTheme.typography.bodySmall)
                    profiles.forEach { profile ->
                        TextButton(onClick = {
                            profilesStore.setActiveProfile(profile)
                            activeProfile = profile
                            showProfile = false
                            if (!profile.notificationConfirmed) {
                                pendingProfile = profile
                                showPendingNotification = true
                                whatsappOpened = false
                            }
                        }) {
                            Text("${if (profile.mobile == activeProfile?.mobile) "✓ " else ""}${profile.name} • ${profile.role}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showProfile = false
                    nameInput = ""; mobileInput = ""; locationInput = ""; registrationError = null
                    registerAsAdmin = false
                    adminNumberDraft = profilesStore.adminWhatsappNumber()
                    showRegistration = true
                }) { Text("＋ Add / Register user") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showProfile = false
                    adminNumberDraft = profilesStore.adminWhatsappNumber()
                    showAdminContact = true
                }) { Text("Admin contact") }
            }
        )
    }

    if (showAdmin) {
        AlertDialog(
            onDismissRequest = { showAdmin = false },
            title = { Text("Admin panel • This device") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Registered local profiles: ${profiles.size}")
                    profiles.forEach { profile ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F4EF))) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(profile.name, fontWeight = FontWeight.Bold)
                                Text("${profile.mobile} • ${profile.location}", style = MaterialTheme.typography.bodySmall)
                                Text("${profile.role} • ${if (profile.notificationConfirmed) "Notification confirmed" else "WhatsApp notification pending"}",
                                    style = MaterialTheme.typography.labelSmall)
                                Text("Registered: ${DateFormat.getDateTimeInstance().format(Date(profile.registeredAt))}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("This panel only shows registrations saved on this phone; it does not receive registrations from other devices.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF64756C))
                }
            },
            confirmButton = { TextButton(onClick = { showAdmin = false }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    showAdmin = false
                    adminNumberDraft = profilesStore.adminWhatsappNumber()
                    showAdminContact = true
                }) { Text("WhatsApp settings") }
            }
        )
    }

    if (showAdminContact) {
        AlertDialog(
            onDismissRequest = { showAdminContact = false },
            title = { Text("Admin WhatsApp contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the admin number with country code. A 10-digit Indian mobile number is accepted; +91 is added automatically.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(adminNumberDraft, { adminNumberDraft = it },
                        Modifier.fillMaxWidth(), label = { Text("Admin WhatsApp number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        profilesStore.setAdminWhatsappNumber(adminNumberDraft)
                        showAdminContact = false
                        onStatus("Admin WhatsApp contact saved on this device.")
                        pendingProfile?.let(::openRegistrationWhatsApp)
                    } catch (error: IllegalArgumentException) {
                        onStatus(error.message ?: "Invalid WhatsApp number.")
                    }
                }) { Text("Save contact") }
            },
            dismissButton = { TextButton(onClick = { showAdminContact = false }) { Text("Cancel") } }
        )
    }

    if (showPendingNotification && pendingProfile != null) {
        val pending = pendingProfile!!
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Admin notification required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your profile is saved locally. Send these details to the admin in WhatsApp, then return here.")
                    Text("Name: ${pending.name}\nMobile: ${pending.mobile}\nLocation: ${pending.location}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        whatsappOpened = false
                        openRegistrationWhatsApp(pending)
                    }) { Text("Open WhatsApp and send details") }
                    Text(if (whatsappOpened)
                        "WhatsApp was opened with the message prepared. Complete sending in WhatsApp."
                    else "Open WhatsApp to continue. If the admin number is missing, configure it first.",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF64756C))
                }
            },
            confirmButton = {
                TextButton(enabled = whatsappOpened, onClick = {
                    profilesStore.confirmAdminNotification(pending.mobile)
                    profiles = profilesStore.profiles()
                    activeProfile = profilesStore.activeProfile()
                    showPendingNotification = false
                    pendingProfile = null
                    whatsappOpened = false
                    onStatus("Registration marked as notified. WhatsApp delivery is not independently verified.")
                }) { Text("I have sent it") }
            }
        )
    }

    if (editingDocument != null) {
        AlertDialog(
            onDismissRequest = { editingDocument = null },
            title = { Text("Edit document details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(editName, { editName = it }, Modifier.fillMaxWidth(),
                        label = { Text("Display name") }, singleLine = true)
                    OutlinedTextField(editCategory, { editCategory = it }, Modifier.fillMaxWidth(),
                        label = { Text("Category (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val document = editingDocument ?: return@TextButton
                    scope.launch {
                        try {
                            repository.updateUserMetadata(document.id, editName, editCategory)
                            onStatus("Document details updated.")
                        } catch (_: IllegalArgumentException) {
                            onStatus("Display name cannot be empty.")
                        }
                        editingDocument = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingDocument = null }) { Text("Cancel") } }
        )
    }

    if (passwordRequest != null) {
        AlertDialog(
            onDismissRequest = onPasswordCancel,
            title = { Text("Unlock protected PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the PDF open password. FileKhoj processes it on this device and does not save the password.")
                    OutlinedTextField(passwordText, { passwordText = it }, Modifier.fillMaxWidth(),
                        label = { Text("PDF password") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            onPasswordSubmit(passwordRequest, passwordText); passwordText = ""
                        }))
                    passwordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { onPasswordSubmit(passwordRequest, passwordText); passwordText = "" }) {
                    Text("Unlock & save")
                }
            },
            dismissButton = { TextButton(onClick = onPasswordCancel) { Text("Skip") } }
        )
    }

    if (showFiltersDialog) {
        AlertDialog(
            onDismissRequest = { showFiltersDialog = false },
            title = { Text("Filter your library") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("File type", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TypeFilter.values().forEach { value ->
                            FilterChip(selected = draftTypeFilter == value, onClick = { draftTypeFilter = value },
                                label = { Text(value.label) })
                        }
                    }
                    Text("Date added", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        DateFilter.values().forEach { value ->
                            FilterChip(selected = draftDateFilter == value, onClick = { draftDateFilter = value },
                                label = { Text(value.label) })
                        }
                    }
                    Text("PDF signature", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        SignatureFilter.values().forEach { value ->
                            FilterChip(selected = draftSignatureFilter == value, onClick = { draftSignatureFilter = value },
                                label = { Text(value.label) })
                        }
                    }
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        categoryOptions.forEach { value ->
                            FilterChip(selected = draftCategory == value, onClick = { draftCategory = value },
                                label = { Text(value) })
                        }
                    }
                    FilterChip(selected = draftFavouritesOnly, onClick = { draftFavouritesOnly = !draftFavouritesOnly },
                        label = { Text("★ Favourites only") })
                    Text("Preview density", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        PreviewMode.values().forEach { value ->
                            FilterChip(selected = draftPreviewMode == value, onClick = { draftPreviewMode = value },
                                label = { Text(value.label) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    typeFilter = draftTypeFilter
                    dateFilter = draftDateFilter
                    signatureFilter = draftSignatureFilter
                    selectedCategory = draftCategory
                    favouritesOnly = draftFavouritesOnly
                    previewMode = draftPreviewMode
                    showFiltersDialog = false
                }) { Text("Apply filters") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        draftTypeFilter = TypeFilter.ALL
                        draftDateFilter = DateFilter.ANY
                        draftSignatureFilter = SignatureFilter.ANY
                        draftCategory = "All categories"
                        draftFavouritesOnly = false
                        draftPreviewMode = PreviewMode.MEDIUM
                    }) { Text("Reset") }
                    TextButton(onClick = { showFiltersDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    if (showBackupDialog) {
        val backupPrefs = remember(context) {
            context.getSharedPreferences(ScheduledBackupWorker.PREFS, Context.MODE_PRIVATE)
        }
        var selectedInterval by remember {
            mutableStateOf(backupPrefs.getLong(ScheduledBackupWorker.KEY_INTERVAL_DAYS, 7L).toInt().takeIf { it in setOf(1, 7, 30) } ?: 7)
        }
        val status = statusMessage.orEmpty()
        val operationInProgress = status.startsWith("Preparing document backup") ||
            status.startsWith("Restoring backup") ||
            status.startsWith("Validating backup") ||
            status.startsWith("Choose a FileKhoj backup")
        AlertDialog(
            onDismissRequest = { if (!operationInProgress) showBackupDialog = false },
            title = { Text("Backup & Restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Create a portable ZIP containing imported document bytes and library metadata.")
                    Text("Automatic local backup interval", style = MaterialTheme.typography.labelLarge)
                    listOf(1 to "Every day", 7 to "Every 7 days", 30 to "Every month").forEach { (days, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedInterval == days,
                                enabled = !operationInProgress,
                                onClick = { selectedInterval = days }
                            )
                            Text(label)
                        }
                    }
                    Text("Scheduled backups are stored on this phone. Google Drive upload is not connected yet.", style = MaterialTheme.typography.bodySmall)
                    if (operationInProgress) {
                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (status.isNotBlank()) {
                        Surface(
                            color = if (status.contains("failed", ignoreCase = true) || status.contains("could not", ignoreCase = true))
                                MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(status, modifier = Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val lastBackup = backupPrefs.getLong(ScheduledBackupWorker.KEY_LAST_SUCCESS, 0L)
                    if (lastBackup > 0L) Text(
                        "Last automatic local backup: ${DateFormat.getDateTimeInstance().format(Date(lastBackup))}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = !operationInProgress, onClick = { onBackupSchedule(selectedInterval) }) { Text("Save schedule") }
                    TextButton(enabled = !operationInProgress, onClick = onBackupClick) { Text("Back up now / Export ZIP") }
                    TextButton(enabled = !operationInProgress, onClick = onRestoreClick) { Text("Restore from ZIP") }
                }
            },
            dismissButton = {
                TextButton(enabled = !operationInProgress, onClick = { onBackupSchedule(null) }) { Text("Disable schedule") }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove from FileKhoj?") },
            text = { Text("First confirmation: remove ${selectedIds.intersect(visibleIds).size} selected item(s) from the library. Original files are not deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirmation = false; showFinalDeleteConfirmation = true }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") } }
        )
    }
    if (showFinalDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinalDeleteConfirmation = false },
            title = { Text("Confirm removal") },
            text = { Text("Are you sure? This removes the selected entries and any FileKhoj-managed private copies. Source documents outside FileKhoj are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.intersect(visibleIds).toList()
                    showFinalDeleteConfirmation = false
                    scope.launch {
                        val result = repository.deleteDocuments(ids)
                        selectedIds = emptySet()
                        onStatus("Removed ${result.deleted} item(s) from FileKhoj" +
                            if (result.failed > 0) "; ${result.failed} could not be removed." else ". Original source files were kept.")
                    }
                }) { Text("Remove permanently") }
            },
            dismissButton = { TextButton(onClick = { showFinalDeleteConfirmation = false }) { Text("Cancel") } }
        )
    }
    if (customizingDocument != null) {
        AlertDialog(
            onDismissRequest = { customizingDocument = null },
            title = { Text("Customize file card") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Card colour")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFFFFFFFF.toInt(), 0xFFEAF2EC.toInt(), 0xFFFFF0D5.toInt(),
                            0xFFE8F0FE.toInt(), 0xFFFCE8E6.toInt(), 0xFFF1E8FF.toInt()).forEach { color ->
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(color))
                                .clickable { customColorDraft = color },
                                contentAlignment = Alignment.Center) {
                                if (customColorDraft == color) Text("✓", color = Color(0xFF07583D))
                            }
                        }
                    }
                    OutlinedTextField(customLabelDraft, { customLabelDraft = it },
                        Modifier.fillMaxWidth(), label = { Text("Labels (comma separated)") },
                        placeholder = { Text("Urgent, Aadhaar, Family") })
                    Text("Labels appear at the bottom of this file card.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val doc = customizingDocument ?: return@TextButton
                    val labels = customLabelDraft.split(",").map(String::trim).filter(String::isNotBlank).distinct().take(6)
                    cardPrefs.edit().putInt("color_${doc.id}", customColorDraft)
                        .putString("labels_${doc.id}", labels.joinToString("\u001F")).apply()
                    cardMetadataVersion++
                    customizingDocument = null
                    onStatus("Card colour and labels saved.")
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val doc = customizingDocument
                        if (doc != null) cardPrefs.edit().remove("labels_${doc.id}").remove("color_${doc.id}").apply()
                        cardMetadataVersion++
                        customizingDocument = null
                    }) { Text("Clear") }
                    TextButton(onClick = { customizingDocument = null }) { Text("Cancel") }
                }
            }
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
    onEdit: () -> Unit,
    onEditPdf: () -> Unit,
    onDelete: () -> Unit,
    onFavourite: () -> Unit,
    onCustomize: () -> Unit,
    customColor: Int,
    customLabels: List<String>
) {
    val isPdf = document.mimeType.contains("pdf", true)
    val isImage = document.mimeType.startsWith("image/")
    val glyph = when {
        isPdf -> "PDF"
        isImage -> "IMG"
        document.mimeType.contains("word", true) -> "DOC"
        else -> "FILE"
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, document.sourceUri, document.managedPath, mode) {
        value = if (mode == PreviewMode.DETAILS) null else withContext(Dispatchers.IO) {
            loadPreviewBitmap(context, document)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(customColor)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (isPdf) Color(0xFFFFE8E5) else Color(0xFFE6F1E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(glyph, color = if (isPdf) Color(0xFFB83D35) else Color(0xFF07583D),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).clickable(onClick = onClick),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(document.displayName, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("${document.mimeType} • ${formatSize(document.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF66766D),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onFavourite) {
                    Text(if (document.isFavourite) "★" else "☆",
                        color = if (document.isFavourite) Color(0xFFB88A24) else Color(0xFF6E7D74),
                        fontSize = 20.sp)
                }
            }
            if (mode != PreviewMode.DETAILS) {
                val previewHeight = when (mode) {
                    PreviewMode.SMALL -> 54.dp
                    PreviewMode.MEDIUM -> 110.dp
                    PreviewMode.LARGE -> 190.dp
                    PreviewMode.DETAILS -> 0.dp
                }
                if (bitmap != null) {
                    Image(bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Preview of ${document.displayName}",
                        modifier = Modifier.fillMaxWidth().height(previewHeight),
                        contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
                        Text(if (isPdf) "PDF • first-page preview unavailable" else "Preview unavailable",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF78867E))
                    }
                }
            }
            if (mode == PreviewMode.DETAILS || mode == PreviewMode.MEDIUM || mode == PreviewMode.LARGE) {
                Text("Added ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(document.dateAdded))}",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF66766D))
                Text(if (document.isFavourite) "★ Favourite • ${document.indexStatus}" else "Index: ${document.indexStatus}",
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF496354))
                val signatureLabel = when (document.signatureStatus) {
                    DocumentEntity.SIGNATURE_STATUS_VERIFIED ->
                        "✓ Verified by Shabana Multi Service Verifier Tool • certificate chain trusted; revocation not checked"
                    DocumentEntity.SIGNATURE_STATUS_UNTRUSTED ->
                        "⚠ Signature found • signer certificate is not trusted"
                    DocumentEntity.SIGNATURE_STATUS_EXPIRED ->
                        "✓ Signature cryptographically valid"
                    DocumentEntity.SIGNATURE_STATUS_VALID_AT_SIGNING_TIME ->
                        "⚠ Signature cryptographically valid • certificate matched the PDF's claimed signing date; trusted timestamp and certificate trust not confirmed"
                    DocumentEntity.SIGNATURE_STATUS_INVALID ->
                        "✕ Invalid signature • cryptographic verification failed"
                    DocumentEntity.SIGNATURE_STATUS_INCOMPLETE ->
                        "⚠ Signature verification incomplete • could not verify signed bytes"
                    DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING ->
                        "⚠ Signature bytes verified • PDF has changes after signing"
                    DocumentEntity.SIGNATURE_STATUS_UNSIGNED ->
                        "No digital signature"
                    else -> "Signature not checked"
                }
                val signatureColor = when (document.signatureStatus) {
                    DocumentEntity.SIGNATURE_STATUS_VERIFIED,
                    DocumentEntity.SIGNATURE_STATUS_EXPIRED -> Color(0xFF188038)
                    DocumentEntity.SIGNATURE_STATUS_INVALID -> Color(0xFFB3261E)
                    DocumentEntity.SIGNATURE_STATUS_UNTRUSTED,
                    DocumentEntity.SIGNATURE_STATUS_VALID_AT_SIGNING_TIME,
                    DocumentEntity.SIGNATURE_STATUS_INCOMPLETE,
                    DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING -> Color(0xFF9A6700)
                    else -> Color(0xFF66766D)
                }
                var signatureExpanded by remember(document.id) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { signatureExpanded = !signatureExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (document.signatureStatus) {
                            DocumentEntity.SIGNATURE_STATUS_VERIFIED -> "✓ Signature valid"
                            DocumentEntity.SIGNATURE_STATUS_EXPIRED -> "✓ Signature cryptographically valid"
                            DocumentEntity.SIGNATURE_STATUS_UNSIGNED -> "○ No digital signature"
                            DocumentEntity.SIGNATURE_STATUS_INVALID -> "✕ Signature invalid"
                            DocumentEntity.SIGNATURE_STATUS_UNTRUSTED -> "⚠ Signature untrusted"
                            DocumentEntity.SIGNATURE_STATUS_VALID_AT_SIGNING_TIME -> "⚠ Valid at signing time"
                            DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING -> "⚠ Changed after signing"
                            DocumentEntity.SIGNATURE_STATUS_INCOMPLETE -> "⚠ Verification incomplete"
                            else -> "Signature not checked"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = signatureColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(if (signatureExpanded) "Less ▴" else "Details ▾",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF496354))
                }
                if (signatureExpanded) {
                    Text(signatureLabel, style = MaterialTheme.typography.bodySmall, color = signatureColor)
                    if (document.signatureStatus == DocumentEntity.SIGNATURE_STATUS_EXPIRED) {
                        Text("⚠ Certificate expired now; trusted signing time and certificate trust are not established.",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A6700))
                    }
                }
            }
            if (customLabels.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    customLabels.take(6).forEach { label ->
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFFE7EFE8)) {
                            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall, color = Color(0xFF214D39),
                                maxLines = 1)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                    Text("✎", fontSize = 18.sp, color = Color(0xFF07583D))
                }
                if (isPdf) {
                    TextButton(onClick = onEditPdf, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        Text("▤✎", fontSize = 16.sp, color = Color(0xFF07583D))
                    }
                }
                TextButton(onClick = onCustomize, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                    Text("🏷", fontSize = 16.sp)
                }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                    Text("🗑", fontSize = 16.sp, color = Color(0xFFB3261E))
                }
                Button(onClick = onClick, shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF07583D))) { Text("Open") }
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
            val descriptor = document.managedPath?.let { path ->
                val managedFile = File(path)
                if (managedFile.isFile) ParcelFileDescriptor.open(managedFile, ParcelFileDescriptor.MODE_READ_ONLY)
                else null
            } ?: context.contentResolver.openFileDescriptor(uri, "r")
            descriptor?.use { pdfDescriptor ->
                PdfRenderer(pdfDescriptor).use { renderer ->
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
