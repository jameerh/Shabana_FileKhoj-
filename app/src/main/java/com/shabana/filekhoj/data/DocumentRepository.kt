package com.shabana.filekhoj.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class ImportDocumentResult(
    val imported: Int,
    val alreadyPresent: Int,
    val failed: Int,
    val passwordRequiredUris: List<Uri> = emptyList(),
    val indexingQueueFailures: Int = 0
)

data class DeleteDocumentResult(val deleted: Int, val failed: Int)

class DocumentRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = DocumentDatabase.getInstance(appContext).documentDao()
    private val securePdfImporter by lazy { SecurePdfImporter(appContext) }

    fun observeDocuments(): Flow<List<DocumentEntity>> = dao.observeAll()
    fun observeProfiles(): Flow<List<DocumentProfileEntity>> = dao.observeProfiles()
    fun observeProfileAssignments(): Flow<List<DocumentProfileAssignmentEntity>> = dao.observeProfileAssignments()
    suspend fun ensureDefaultProfile(): Long = dao.ensureDefaultProfile()

    suspend fun seedDefaultProfilesIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = appContext.getSharedPreferences("filekhoj_document_profiles", Context.MODE_PRIVATE)
        if (prefs.getBoolean("builtins_seeded_v1", false)) return@withContext
        val existing = dao.getProfiles()
        if (existing.isEmpty()) dao.ensureDefaultProfile()
        val currentNames = dao.getProfiles().map { it.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
        DocumentProfileDefaults.profiles.forEach { profile ->
            if (profile.name.lowercase(java.util.Locale.ROOT) !in currentNames) {
                val now = System.currentTimeMillis()
                dao.insertProfile(
                    DocumentProfileEntity(
                        name = profile.name, icon = profile.icon, colorArgb = profile.colorArgb,
                        isDefault = profile.name == DocumentProfileEntity.DEFAULT_NAME,
                        sortOrder = profile.sortOrder, createdAt = now, updatedAt = now
                    )
                )
            }
        }
        val defaultId = dao.ensureDefaultProfile()
        dao.getAllForBackup().forEach { document ->
            dao.insertProfileAssignment(DocumentProfileAssignmentEntity(document.id, defaultId, document.category))
        }
        prefs.edit().putBoolean("builtins_seeded_v1", true).commit()
    }

    suspend fun createProfile(name: String, icon: String = "folder", colorArgb: Int = 0xFF07583D.toInt()): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Profile name cannot be empty." }
        require(normalized.length <= 40) { "Profile name must be 40 characters or fewer." }
        require(dao.getProfiles().none { it.name.equals(normalized, ignoreCase = true) }) {
            "A profile with this name already exists."
        }
        val now = System.currentTimeMillis()
        val nextOrder = (dao.getProfiles().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return dao.insertProfile(
            DocumentProfileEntity(
                name = normalized, icon = icon, colorArgb = colorArgb,
                isDefault = false, sortOrder = nextOrder, createdAt = now, updatedAt = now
            )
        )
    }
    suspend fun renameProfile(profileId: Long, name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 40) { "Enter a profile name of 1–40 characters." }
        val profile = dao.getProfile(profileId) ?: error("Profile not found.")
        require(dao.getProfiles().none { it.id != profileId && it.name.equals(normalized, true) }) {
            "A profile with this name already exists."
        }
        dao.updateProfile(profile.copy(name = normalized, updatedAt = System.currentTimeMillis()))
    }
    suspend fun assignDocumentsToProfile(documentIds: Collection<Long>, profileId: Long) {
        require(dao.getProfile(profileId) != null) { "Destination profile no longer exists." }
        dao.moveDocumentsBetweenProfiles(documentIds.distinct(), null, profileId)
    }
    suspend fun moveDocumentsToProfile(documentIds: Collection<Long>, sourceProfileId: Long?, targetProfileId: Long) {
        require(dao.getProfile(targetProfileId) != null) { "Destination profile no longer exists." }
        if (sourceProfileId != null) require(dao.getProfile(sourceProfileId) != null) { "Source profile no longer exists." }
        dao.moveDocumentsBetweenProfiles(documentIds.distinct(), sourceProfileId, targetProfileId)
    }
    suspend fun removeDocumentFromProfile(documentId: Long, profileId: Long) {
        val assignments = dao.getProfileIdsForDocument(documentId)
        require(assignments.size > 1) { "A document must remain assigned to at least one profile." }
        dao.removeProfileAssignment(documentId, profileId)
    }
    suspend fun deleteProfile(profileId: Long) {
        val profile = dao.getProfile(profileId) ?: return
        require(!profile.isDefault) { "The default profile cannot be deleted." }
        val defaultId = dao.ensureDefaultProfile()
        val documentIds = dao.getDocumentIdsForProfile(profileId)
        dao.insertProfileAssignments(documentIds.map { DocumentProfileAssignmentEntity(it, defaultId) })
        dao.deleteProfile(profile)
    }
    suspend fun setProfileCategory(documentId: Long, profileId: Long, category: String?) {
        dao.updateProfileCategory(documentId, profileId, category?.trim()?.takeIf(String::isNotEmpty))
    }
    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Reprocess the existing library once after the OCR quality gate is introduced.
     * This corrects legacy INDEXED labels written without confidence/coverage checks.
     */
    suspend fun reindexExistingDocumentsForOcrQualityGate() = withContext(Dispatchers.IO) {
        val migrationPrefs = appContext.getSharedPreferences("filekhoj_index_quality", Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean("quality_gate_reindex_v1_complete", false)) return@withContext

        dao.getAllForBackup().forEach { document ->
            dao.updateIndexStatus(document.id, DocumentEntity.INDEX_STATUS_QUEUED)
            val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                .setInputData(workDataOf(DocumentIndexWorker.KEY_DOCUMENT_ID to document.id))
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "filekhoj-index-${document.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
        migrationPrefs.edit().putBoolean("quality_gate_reindex_v1_complete", true).commit()
    }

    suspend fun searchDocuments(query: String): List<DocumentEntity> = withContext(Dispatchers.IO) {
        val ftsQuery = SearchQueryNormalizer.toFtsQuery(query) ?: return@withContext emptyList()
        try {
            dao.searchFullText(ftsQuery)
        } catch (_: Exception) {
            // Malformed FTS expressions must never crash the library search.
            emptyList()
        }
    }

    suspend fun importDocuments(
        uris: List<Uri>,
        passwordsByUri: Map<String, String> = emptyMap(),
        profileId: Long? = null,
        copyToManagedStorage: Boolean = false
    ): ImportDocumentResult = withContext(Dispatchers.IO) {
        val destinationProfileId = profileId ?: dao.ensureDefaultProfile()
        var imported = 0
        var alreadyPresent = 0
        var failed = 0
        var indexingQueueFailures = 0
        val passwordRequired = mutableListOf<Uri>()

        uris.distinctBy { it.toString() }.forEach { uri ->
            var managedCopy: File? = null
            try {
                val existingDocument = dao.findBySourceUri(uri.toString())
                if (existingDocument != null) {
                    dao.insertProfileAssignment(
                        DocumentProfileAssignmentEntity(existingDocument.id, destinationProfileId)
                    )
                    alreadyPresent++
                    return@forEach
                }

                val metadata = queryMetadata(uri)
                var encryptedSource = false
                var storedDecrypted = false
                var signatureStatus = DocumentEntity.SIGNATURE_STATUS_UNSIGNED
                var signatureCount = 0

                if (metadata.mimeType.contains("pdf", ignoreCase = true) ||
                    metadata.displayName.endsWith(".pdf", ignoreCase = true)
                ) {
                    when (val preparation = securePdfImporter.prepare(uri, passwordsByUri[uri.toString()])) {
                        is PdfPreparationResult.OriginalUnencrypted -> {
                            signatureStatus = preparation.signatureStatus
                            signatureCount = preparation.signatureCount
                        }
                        is PdfPreparationResult.DecryptedCopy -> {
                            managedCopy = preparation.file
                            encryptedSource = true
                            storedDecrypted = true
                            // The managed PDF is a rewritten derivative, not the signed source bytes.
                            signatureStatus = DocumentEntity.SIGNATURE_STATUS_UNKNOWN
                            signatureCount = 0
                        }
                        PdfPreparationResult.PasswordRequired -> {
                            passwordRequired += uri
                            return@forEach
                        }
                        is PdfPreparationResult.Failed -> {
                            failed++
                            return@forEach
                        }
                    }
                }

                if (copyToManagedStorage && managedCopy == null) {
                    managedCopy = copyIntoManagedStorage(uri, metadata.displayName)
                }

                val rowId = dao.insert(
                    DocumentEntity(
                        displayName = metadata.displayName,
                        mimeType = metadata.mimeType,
                        sizeBytes = managedCopy?.length() ?: metadata.sizeBytes,
                        sourceUri = uri.toString(),
                        dateAdded = System.currentTimeMillis(),
                        sourceLastModified = metadata.lastModified,
                        isEncryptedSource = encryptedSource,
                        isStoredDecrypted = storedDecrypted,
                        managedPath = managedCopy?.absolutePath,
                        signatureStatus = signatureStatus,
                        signatureCount = signatureCount
                    )
                )
                if (rowId == -1L) {
                    alreadyPresent++
                    managedCopy?.delete()
                } else {
                    dao.insertProfileAssignment(DocumentProfileAssignmentEntity(rowId, destinationProfileId))
                    imported++
                    val queued = runCatching {
                        val indexRequest = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                            .setInputData(workDataOf(DocumentIndexWorker.KEY_DOCUMENT_ID to rowId))
                            .build()
                        WorkManager.getInstance(appContext).enqueueUniqueWork(
                            "filekhoj-index-$rowId",
                            ExistingWorkPolicy.KEEP,
                            indexRequest
                        )
                    }.isSuccess
                    if (!queued) indexingQueueFailures++
                }
            } catch (_: Exception) {
                managedCopy?.delete()
                failed++
            }
        }
        ImportDocumentResult(
            imported, alreadyPresent, failed,
            passwordRequired.distinctBy { it.toString() },
            indexingQueueFailures
        )
    }

    /**
     * Removes library entries, never provider originals. A managed copy is first moved aside;
     * if the Room transaction fails, the file is moved back so the visible row remains usable.
     */
    suspend fun deleteDocuments(ids: List<Long>): DeleteDocumentResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        ids.distinct().forEach { id ->
            var originalFile: File? = null
            var quarantineFile: File? = null
            try {
                val document = dao.findById(id) ?: return@forEach
                val managedPath = document.managedPath
                if (!managedPath.isNullOrBlank()) {
                    val managedFile = File(managedPath).canonicalFile
                    val privateRoot = appContext.filesDir.canonicalFile
                    if (managedFile.path.startsWith(privateRoot.path + File.separator) && managedFile.isFile) {
                        originalFile = managedFile
                        quarantineFile = File(
                            managedFile.parentFile,
                            ".${managedFile.name}.pending-delete-${System.nanoTime()}"
                        )
                        if (!managedFile.renameTo(quarantineFile!!)) {
                            throw IllegalStateException("Could not safely stage managed copy for deletion.")
                        }
                    }
                }

                val removed = try {
                    dao.deleteDocumentCompletely(id)
                } catch (error: Exception) {
                    quarantineFile?.takeIf { it.exists() }?.renameTo(originalFile!!)
                    throw error
                }
                if (removed > 0) {
                    deleted++
                    // A failed cleanup only leaves a private orphan; it never loses the source document.
                    quarantineFile?.delete()
                } else {
                    quarantineFile?.takeIf { it.exists() }?.renameTo(originalFile!!)
                }
            } catch (_: Exception) {
                if (quarantineFile?.exists() == true && originalFile?.exists() == false) {
                    quarantineFile?.renameTo(originalFile!!)
                }
                failed++
            }
        }
        DeleteDocumentResult(deleted, failed)
    }

    suspend fun setFavourite(id: Long, favourite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavourite(id, favourite)
    }

    suspend fun updateUserMetadata(id: Long, displayName: String, category: String?) = withContext(Dispatchers.IO) {
        require(displayName.isNotBlank()) { "Document name cannot be empty." }
        dao.updateUserMetadata(id, displayName.trim(), category?.trim()?.takeIf(String::isNotBlank))
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled document"
        var sizeBytes = 0L
        var lastModified: Long? = null
        val cursor = try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )
        } catch (_: Exception) {
            // Some third-party document providers reject optional metadata columns.
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        }
        cursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf(String::isNotBlank)?.let {
                        displayName = it
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    }
                    val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        lastModified = cursor.getLong(modifiedIndex).takeIf { it > 0L }
                    }
                }
            }
        return SourceMetadata(
            displayName = displayName,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = sizeBytes,
            lastModified = lastModified
        )
    }


    private fun copyIntoManagedStorage(uri: Uri, displayName: String): File {
        val directory = File(appContext.filesDir, "documents/imported")
        check(directory.exists() || directory.mkdirs()) { "Could not create private document storage." }
        val extension = displayName.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
            ?.let { ".$it" }.orEmpty()
        val destination = File(directory, "import-${java.util.UUID.randomUUID()}$extension")
        try {
            val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Could not read shared document.")
            input.use { source ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_MANAGED_IMPORT_BYTES) { "Document exceeds the 512 MB import limit." }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            require(destination.isFile && destination.length() > 0L) { "The selected document is empty." }
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    companion object {
        const val MAX_MANAGED_IMPORT_BYTES = 512L * 1024L * 1024L
    }

    private data class SourceMetadata(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModified: Long?
    )
}
