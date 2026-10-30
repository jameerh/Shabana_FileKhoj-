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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.shabana.filekhoj.data.DocumentEntity
import com.shabana.filekhoj.data.DocumentRepository
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
    private lateinit var repository: DocumentRepository
    private val importStatus = mutableStateOf<String?>(null)
    private val searchHistory = mutableStateOf<List<String>>(emptyList())
    private val passwordQueue = mutableStateOf<List<Uri>>(emptyList())
    private val passwordError = mutableStateOf<String?>(null)
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
    onStatus: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
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
    var editName by remember(editingDocument) { mutableStateOf(editingDocument?.displayName.orEmpty()) }
    var editCategory by remember(editingDocument) { mutableStateOf(editingDocument?.category.orEmpty()) }
    var passwordText by remember(passwordRequest) { mutableStateOf("") }
    var typeMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    var signatureMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val profilesStore = remember(context) { UserRegistrationStore(context) }
    var profiles by remember { mutableStateOf(profilesStore.profiles()) }
    var activeProfile by remember { mutableStateOf(profilesStore.activeProfile()) }
    var showRegistration by remember { mutableStateOf(activeProfile == null) }
    var showProfile by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    var showAdminContact by remember { mutableStateOf(false) }
    var adminNumberDraft by remember { mutableStateOf(profilesStore.adminWhatsappNumber()) }
    var nameInput by remember { mutableStateOf("") }
    var mobileInput by remember { mutableStateOf("") }
    var locationInput by remember { mutableStateOf("") }
    var registrationError by remember { mutableStateOf<String?>(null) }
    var pendingProfile by remember { mutableStateOf<LocalUserProfile?>(null) }
    var showPendingNotification by remember { mutableStateOf(false) }
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
                        favouritesOnly = false
                        scope.launch { drawerState.close() }
                    })
                    NavigationDrawerItem(label = { Text("▤  All documents") }, selected = false, onClick = {
                        favouritesOnly = false
                        typeFilter = TypeFilter.ALL
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
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰", fontSize = 24.sp, color = Color(0xFF07583D))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("शबाना FileKhoj", style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                        Text("तुमचे दस्तऐवज. तुमच्याच मोबाईलमध्ये.",
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF64756C))
                    }
                    IconButton(onClick = { showProfile = true }) {
                        Text(activeProfile?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "♙",
                            fontSize = 22.sp, color = Color(0xFF07583D), fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF064B33))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("शबाना", color = Color(0xFFFFD76B), fontWeight = FontWeight.Bold)
                                    Text("FileKhoj", color = Color.White, fontSize = 32.sp,
                                        fontWeight = FontWeight.ExtraBold)
                                    Text("तुमचे सर्व दस्तऐवज,\nशोधा क्षणार्धात!",
                                        color = Color.White, fontSize = 18.sp, lineHeight = 24.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text("Save once. Search anything. Find instantly.",
                                        color = Color(0xFFD8EBDD), style = MaterialTheme.typography.bodySmall)
                                    Text("PDF  •  फोटो  •  कागदपत्रे  •  OCR शोध",
                                        color = Color(0xFFFFD76B), style = MaterialTheme.typography.labelMedium)
                                }
                                Box(Modifier.size(74.dp).clip(CircleShape).background(Color(0xFF0B6847)),
                                    contentAlignment = Alignment.Center) {
                                    Text("⌕", color = Color(0xFFFFD76B), fontSize = 58.sp)
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("काय शोधायचे आहे?", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, color = Color(0xFF183B2D))
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("नाव, क्रमांक किंवा PDF मधील शब्द शोधा…") },
                                    leadingIcon = { Text("⌕", fontSize = 24.sp) },
                                    trailingIcon = {
                                        if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("Clear") }
                                    },
                                    singleLine = true, shape = RoundedCornerShape(16.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmitted(query) })
                                )
                                Text("फाईलचे नाव आणि उपलब्ध indexed मजकूर — इंटरनेटशिवाय शोधा.",
                                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF66766D))
                            }
                        }
                    }
                    if (searchHistory.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Recent searches", style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF4D6459), fontWeight = FontWeight.SemiBold)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    searchHistory.forEach { recent ->
                                        FilterChip(selected = query.equals(recent, true),
                                            onClick = { query = recent; onSearchSubmitted(recent) },
                                            label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Quick filters", style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF4D6459), fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box {
                                    FilterChip(selected = typeFilter != TypeFilter.ALL,
                                        onClick = { typeMenu = true }, label = { Text(typeFilter.label) })
                                    DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                                        TypeFilter.values().forEach { filter ->
                                            DropdownMenuItem(text = { Text(filter.label) }, onClick = {
                                                typeFilter = filter; typeMenu = false
                                            })
                                        }
                                    }
                                }
                                Box {
                                    FilterChip(selected = dateFilter != DateFilter.ANY,
                                        onClick = { dateMenu = true }, label = { Text(dateFilter.label) })
                                    DropdownMenu(dateMenu, onDismissRequest = { dateMenu = false }) {
                                        DateFilter.values().forEach { filter ->
                                            DropdownMenuItem(text = { Text(filter.label) }, onClick = {
                                                dateFilter = filter; dateMenu = false
                                            })
                                        }
                                    }
                                }
                                Box {
                                    FilterChip(selected = signatureFilter != SignatureFilter.ANY,
                                        onClick = { signatureMenu = true }, label = { Text(signatureFilter.label) })
                                    DropdownMenu(signatureMenu, onDismissRequest = { signatureMenu = false }) {
                                        SignatureFilter.values().forEach { filter ->
                                            DropdownMenuItem(text = { Text(filter.label) }, onClick = {
                                                signatureFilter = filter; signatureMenu = false
                                            })
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    FilterChip(selected = selectedCategory != "All categories",
                                        onClick = { categoryMenu = true }, label = { Text(selectedCategory) })
                                    DropdownMenu(categoryMenu, onDismissRequest = { categoryMenu = false }) {
                                        categoryOptions.forEach { category ->
                                            DropdownMenuItem(text = { Text(category) }, onClick = {
                                                selectedCategory = category; categoryMenu = false
                                            })
                                        }
                                    }
                                }
                                FilterChip(selected = favouritesOnly, onClick = { favouritesOnly = !favouritesOnly },
                                    label = { Text("★ Favourites") })
                                Box {
                                    FilterChip(selected = previewMode != PreviewMode.MEDIUM,
                                        onClick = { viewMenu = true }, label = { Text("View: ${previewMode.label}") })
                                    DropdownMenu(viewMenu, onDismissRequest = { viewMenu = false }) {
                                        PreviewMode.values().forEach { mode ->
                                            DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                                                previewMode = mode; viewMenu = false
                                            })
                                        }
                                    }
                                }
                                TextButton(onClick = {
                                    typeFilter = TypeFilter.ALL
                                    dateFilter = DateFilter.ANY
                                    signatureFilter = SignatureFilter.ANY
                                    favouritesOnly = false
                                    selectedCategory = "All categories"
                                }) { Text("Reset") }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Your library", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold)
                                    Text("${filteredDocuments.size} shown  •  ${documents.size} saved",
                                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF64756C))
                                }
                                Button(onClick = onImportClick, shape = RoundedCornerShape(50),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF07583D))) { Text("＋ Import files") }
                            }
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
                                        Text("${visibleSelectedIds.size} selected", style = MaterialTheme.typography.labelSmall)
                                        TextButton(onClick = { showDeleteConfirmation = true }) { Text("Remove") }
                                        TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
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
                                onDelete = { selectedIds = setOf(document.id); showDeleteConfirmation = true },
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

    if (showRegistration) {
        AlertDialog(
            onDismissRequest = { if (activeProfile != null) showRegistration = false },
            title = { Text(if (activeProfile == null) "Welcome to Shabana FileKhoj" else "Register a profile") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("फक्त आवश्यक माहिती भरा. App name आणि registration date आपोआप जोडली जातील.",
                        style = MaterialTheme.typography.bodySmall)
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
                        val profile = profilesStore.register(nameInput, mobileInput, locationInput)
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
                            onStatus("First profile created as the administrator on this device. Configure the central admin contact before distributing user builds.")
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

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Remove selected documents?") },
            text = { Text("This removes ${visibleSelectedIds.size} item(s) from the FileKhoj library. Original source documents will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.intersect(visibleIds).toList()
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavourite: () -> Unit
) {
    val isPdf = document.mimeType.contains("pdf", true)
    val bitmap by produceState<Bitmap?>(initialValue = null, document.sourceUri, document.managedPath, mode) {
        value = if (mode == PreviewMode.DETAILS) null else withContext(Dispatchers.IO) {
            loadPreviewBitmap(context, document)
        }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
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
                    DocumentEntity.SIGNATURE_STATUS_VERIFIED -> "✓ Source signature + certificate chain verified (revocation not checked)"
                    DocumentEntity.SIGNATURE_STATUS_UNTRUSTED -> "⚠ Signature found — certificate untrusted"
                    DocumentEntity.SIGNATURE_STATUS_INVALID -> "✕ Signature verification failed"
                    DocumentEntity.SIGNATURE_STATUS_INCOMPLETE -> "Signature verification incomplete"
                    DocumentEntity.SIGNATURE_STATUS_UNSIGNED -> "No digital signature"
                    else -> "Signature not checked"
                }
                Text(signatureLabel, style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text("Edit") }
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
