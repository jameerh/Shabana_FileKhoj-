package com.shabana.filekhoj.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, offline-first FileKhoj archive.
 * Restore stages and validates the complete archive before committing any database rows.
 * The archive contains document bytes and library metadata; it never contains PDF passwords.
 */
class BackupRestoreManager(context: Context) {
    private val app = context.applicationContext
    private val dao = DocumentDatabase.getInstance(app).documentDao()
    private val resolver = app.contentResolver

    suspend fun createBackup(destination: File): BackupResult {
        val documents = dao.getAllForBackup()
        require(documents.size <= MAX_DOCUMENTS) { "Too many documents for a FileKhoj backup." }
        val profiles = dao.getProfiles()
        val profilesById = profiles.associateBy { it.id }
        val assignmentsByDocument = dao.getAllProfileAssignments().groupBy { it.documentId }
        val remindersByDocument = dao.getAllReminders().associateBy { it.documentId }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("profiles", JSONArray().apply {
                profiles.forEach { profile ->
                    put(JSONObject()
                        .put("name", profile.name)
                        .put("icon", profile.icon)
                        .put("colorArgb", profile.colorArgb)
                        .put("sortOrder", profile.sortOrder))
                }
            })
        val rows = JSONArray()
        var bytes = 0L
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            documents.forEachIndexed { index, doc ->
                val payloadName = "documents/$index.bin"
                zip.putNextEntry(ZipEntry(payloadName))
                val digest = MessageDigest.getInstance("SHA-256")
                val payloadBytes = openDocument(doc).use { input ->
                    copyAndHash(input, zip, digest, MAX_DOCUMENT_BYTES)
                }
                zip.closeEntry()
                bytes += payloadBytes.first
                require(bytes <= MAX_TOTAL_DOCUMENT_BYTES) { "Backup exceeds the total document size limit." }
                rows.put(JSONObject()
                    .put("entry", payloadName)
                    .put("displayName", doc.displayName)
                    .put("mimeType", doc.mimeType)
                    .put("sizeBytes", doc.sizeBytes)
                    .put("payloadSizeBytes", payloadBytes.first)
                    .put("payloadSha256", payloadBytes.second)
                    .put("dateAdded", doc.dateAdded)
                    .put("sourceLastModified", doc.sourceLastModified ?: JSONObject.NULL)
                    .put("category", doc.category ?: JSONObject.NULL)
                    .put("contentHash", doc.contentHash ?: JSONObject.NULL)
                    .put("isFavourite", doc.isFavourite)
                    .put("signatureStatus", doc.signatureStatus)
                    .put("signatureCount", doc.signatureCount)
                    .put("isEncryptedSource", doc.isEncryptedSource)
                    .put("isStoredDecrypted", doc.isStoredDecrypted)
                    .put("profileAssignments", JSONArray().apply {
                        assignmentsByDocument[doc.id].orEmpty().forEach { assignment ->
                            val profile = profilesById[assignment.profileId] ?: return@forEach
                            put(JSONObject()
                                .put("profileName", profile.name)
                                .put("categoryOverride", assignment.categoryOverride ?: JSONObject.NULL))
                        }
                    })
                    .put("reminder", remindersByDocument[doc.id]?.let { reminder ->
                        JSONObject()
                            .put("label", reminder.label)
                            .put("dueAt", reminder.dueAt)
                            .put("enabled", reminder.enabled)
                    } ?: JSONObject.NULL)
                )
            }
            manifest.put("documents", rows)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        require(destination.length() <= BackupArchiveIO.MAX_ARCHIVE_BYTES) {
            "Backup archive exceeds the allowed file size."
        }
        return BackupResult(documents.size, bytes, destination.length())
    }

    suspend fun restoreBackup(source: File): RestoreResult {
        require(source.isFile && source.length() > 0L) { "Backup archive is empty or missing." }
        require(source.length() <= BackupArchiveIO.MAX_ARCHIVE_BYTES) {
            "Backup archive exceeds the allowed file size."
        }
        val stagingDir = File(app.cacheDir, "filekhoj-restore-${UUID.randomUUID()}")
        check(stagingDir.mkdirs()) { "Could not create private restore staging directory." }
        val stagedEntries = linkedMapOf<String, File>()
        val finalFiles = mutableListOf<File>()
        var databaseCommitted = false
        try {
            var manifestBytes: ByteArray? = null
            var expandedBytes = 0L
            var entryCount = 0
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_DOCUMENTS + 1) { "Backup contains too many archive entries." }
                    require(!entry.isDirectory) { "Directory entries are not supported in FileKhoj backups." }
                    when {
                        entry.name == MANIFEST -> {
                            require(manifestBytes == null) { "Backup contains duplicate manifests." }
                            manifestBytes = readBounded(zip, MAX_MANIFEST_BYTES)
                        }
                        DOCUMENT_ENTRY_PATTERN.matches(entry.name) -> {
                            require(entry.name !in stagedEntries) { "Backup contains duplicate document entries." }
                            val staged = File(stagingDir, "payload-${stagedEntries.size}.bin")
                            val copied = copyBounded(zip, staged, MAX_DOCUMENT_BYTES)
                            expandedBytes += copied
                            require(expandedBytes <= MAX_TOTAL_DOCUMENT_BYTES) {
                                "Backup exceeds the total expanded document size limit."
                            }
                            stagedEntries[entry.name] = staged
                        }
                        else -> throw IllegalArgumentException("Unexpected file in backup archive.")
                    }
                    zip.closeEntry()
                }
            }

            val root = manifestBytes?.let { JSONObject(String(it, Charsets.UTF_8)) }
                ?: throw IllegalArgumentException("This is not a FileKhoj backup.")
            require(root.optString("format") == FORMAT) { "Unsupported backup format." }
            val archiveVersion = root.optInt("version", -1)
            require(archiveVersion in MIN_SUPPORTED_VERSION..VERSION) {
                "Unsupported FileKhoj backup version."
            }
            val rows = root.optJSONArray("documents")
                ?: throw IllegalArgumentException("Backup manifest has no document list.")
            require(rows.length() <= MAX_DOCUMENTS) { "Backup contains too many documents." }

            val referencedEntries = mutableSetOf<String>()
            val restoreDir = File(app.filesDir, "documents/backup_restore")
            if (!restoreDir.exists() && !restoreDir.mkdirs()) {
                throw IllegalStateException("Could not create document restore directory.")
            }
            val pendingDocuments = mutableListOf<DocumentEntity>()
            val pendingFiles = mutableListOf<File>()
            val pendingAssignmentNames = mutableListOf<List<Pair<String, String?>>>()
            val pendingReminders = mutableListOf<Pair<String, Long>?>()
            var expectedBytes = 0L

            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i)
                    ?: throw IllegalArgumentException("Backup contains an invalid document record.")
                val entryName = row.optString("entry")
                require(DOCUMENT_ENTRY_PATTERN.matches(entryName)) { "Backup contains an invalid document entry name." }
                require(referencedEntries.add(entryName)) { "Backup manifest references a document more than once." }
                val staged = stagedEntries[entryName]
                    ?: throw IllegalArgumentException("Backup is incomplete: missing document payload.")
                val expectedHash = row.optString("payloadSha256").takeIf(String::isNotBlank)
                val expectedSize = row.optLong("payloadSizeBytes", -1L)
                if (archiveVersion >= 2) {
                    require(expectedHash != null && expectedHash.matches(Regex("[0-9a-fA-F]{64}"))) {
                        "Version 2 backup is missing a valid SHA-256 payload digest."
                    }
                    require(expectedSize >= 0L) {
                        "Version 2 backup is missing a payload byte count."
                    }
                }
                if (expectedHash != null) {
                    require(expectedHash.equals(sha256(staged), ignoreCase = true)) {
                        "Backup document integrity check failed."
                    }
                }
                if (expectedSize >= 0L) {
                    require(expectedSize == staged.length()) { "Backup document size check failed." }
                }
                expectedBytes += staged.length()
                require(expectedBytes <= MAX_TOTAL_DOCUMENT_BYTES) {
                    "Backup exceeds the total expanded document size limit."
                }

                val safeName = row.optString("displayName", "Restored document")
                    .take(MAX_DISPLAY_NAME_LENGTH)
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "Restored document" }
                val target = File.createTempFile("filekhoj-restored-", ".bin", restoreDir)
                finalFiles += target
                staged.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
                val restoredMimeType = row.optString("mimeType", "application/octet-stream")
                val restoredIsPdf = restoredMimeType.contains("pdf", ignoreCase = true) ||
                    safeName.endsWith(".pdf", ignoreCase = true)
                val encryptedSource = row.optBoolean("isEncryptedSource", false)
                val storedDecrypted = if (row.has("isStoredDecrypted")) {
                    row.optBoolean("isStoredDecrypted", false)
                } else {
                    // Version 1 archives did not record whether the payload was an unlocked
                    // derivative. Conservatively avoid presenting a signed PDF derivative as
                    // independently verified when the old manifest cannot disambiguate it.
                    val signatureStatus = row.optString(
                        "signatureStatus",
                        DocumentEntity.SIGNATURE_STATUS_UNKNOWN
                    )
                    row.optString("mimeType", "").contains("pdf", ignoreCase = true) &&
                        signatureStatus !in setOf(
                            DocumentEntity.SIGNATURE_STATUS_UNKNOWN,
                            DocumentEntity.SIGNATURE_STATUS_UNSIGNED
                        )
                }
                pendingFiles += target
                pendingDocuments += DocumentEntity(
                    displayName = safeName,
                    mimeType = restoredMimeType,
                    sizeBytes = target.length(),
                    sourceUri = uri.toString(),
                    dateAdded = row.optLong("dateAdded", System.currentTimeMillis()),
                    sourceLastModified = if (row.isNull("sourceLastModified")) null else row.optLong("sourceLastModified"),
                    category = if (row.isNull("category")) null else row.optString("category"),
                    contentHash = if (storedDecrypted || row.isNull("contentHash")) null else row.optString("contentHash"),
                    // Archive manifests are unsigned and user-editable. Never trust a restored
                    // signature verdict from metadata; it must be re-evaluated against the restored bytes.
                    signatureStatus = if (restoredIsPdf) {
                        DocumentEntity.SIGNATURE_STATUS_UNKNOWN
                    } else {
                        DocumentEntity.SIGNATURE_STATUS_UNSIGNED
                    },
                    signatureCount = 0,
                    isEncryptedSource = encryptedSource,
                    isStoredDecrypted = storedDecrypted,
                    managedPath = target.absolutePath,
                    isFavourite = row.optBoolean("isFavourite", false)
                )
                val rowAssignments = mutableListOf<Pair<String, String?>>()
                val assignments = row.optJSONArray("profileAssignments")
                if (assignments != null) {
                    for (assignmentIndex in 0 until assignments.length()) {
                        val assignment = assignments.optJSONObject(assignmentIndex) ?: continue
                        val profileName = assignment.optString("profileName").trim()
                        if (profileName.isNotEmpty()) {
                            val category = if (assignment.isNull("categoryOverride")) null
                                else assignment.optString("categoryOverride").takeIf(String::isNotBlank)
                            rowAssignments += profileName.take(MAX_PROFILE_NAME_LENGTH) to category
                        }
                    }
                }
                pendingAssignmentNames += rowAssignments.distinctBy { it.first.lowercase(java.util.Locale.ROOT) }
                val reminderObject = row.optJSONObject("reminder")
                val reminderLabel = reminderObject?.optString("label")?.trim().orEmpty()
                val reminderDueAt = reminderObject?.optLong("dueAt", -1L) ?: -1L
                pendingReminders += if (reminderObject != null && reminderLabel.isNotBlank() &&
                    reminderLabel.length <= MAX_REMINDER_LABEL_LENGTH && reminderDueAt > 0L &&
                    reminderObject.optBoolean("enabled", true)
                ) reminderLabel to reminderDueAt else null
            }
            require(referencedEntries == stagedEntries.keys) {
                "Backup contains document payloads not referenced by its manifest."
            }

            // Resolve profile names only after every archive entry and payload has passed validation.
            val defaultProfileId = dao.ensureDefaultProfile()
            val profileIdByName = dao.getProfiles().associate { it.name.lowercase(java.util.Locale.ROOT) to it.id }.toMutableMap()
            val profileDefinitions = root.optJSONArray("profiles")
            if (profileDefinitions != null && archiveVersion >= 3) {
                for (profileIndex in 0 until profileDefinitions.length()) {
                    val definition = profileDefinitions.optJSONObject(profileIndex) ?: continue
                    val name = definition.optString("name").trim().take(MAX_PROFILE_NAME_LENGTH)
                    if (name.isBlank()) continue
                    val key = name.lowercase(java.util.Locale.ROOT)
                    if (key !in profileIdByName) {
                        val now = System.currentTimeMillis()
                        val createdId = dao.insertProfile(
                            DocumentProfileEntity(
                                name = name,
                                icon = definition.optString("icon", "folder").take(32),
                                colorArgb = definition.optInt("colorArgb", 0xFF07583D.toInt()),
                                isDefault = false,
                                sortOrder = definition.optInt("sortOrder", dao.getProfiles().size).coerceAtLeast(0),
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        profileIdByName[key] = createdId
                    }
                }
            }
            pendingAssignmentNames.flatten().map { it.first }.distinctBy { it.lowercase(java.util.Locale.ROOT) }.forEach { name ->
                val key = name.lowercase(java.util.Locale.ROOT)
                if (key !in profileIdByName) {
                    val now = System.currentTimeMillis()
                    val createdId = dao.insertProfile(
                        DocumentProfileEntity(
                            name = name.take(MAX_PROFILE_NAME_LENGTH), icon = "folder",
                            colorArgb = 0xFF07583D.toInt(), isDefault = false,
                            sortOrder = dao.getProfiles().size, createdAt = now, updatedAt = now
                        )
                    )
                    profileIdByName[key] = createdId
                }
            }
            val assignmentsByIndex = pendingAssignmentNames.map { assignments ->
                assignments.mapNotNull { (name, category) ->
                    profileIdByName[name.lowercase(java.util.Locale.ROOT)]?.let { it to category }
                }
            }

            // All payloads and workspace mappings are committed together in one Room transaction.
            val insertedIds = dao.insertDocumentsWithProfileAssignmentsAtomically(
                pendingDocuments, assignmentsByIndex, defaultProfileId, pendingReminders
            )
            databaseCommitted = true
            var restored = 0
            var skipped = 0
            var restoredBytes = 0L
            val workIds = mutableListOf<Long>()
            insertedIds.forEachIndexed { index, id ->
                val file = pendingFiles[index]
                if (id == -1L) {
                    file.delete()
                    skipped++
                } else {
                    restored++
                    restoredBytes += file.length()
                    workIds += id
                }
            }
            var indexingQueueFailures = 0
            workIds.forEach { id ->
                runCatching {
                    val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                        .setInputData(workDataOf(DocumentIndexWorker.KEY_DOCUMENT_ID to id))
                        .build()
                    WorkManager.getInstance(app).enqueueUniqueWork(
                        "filekhoj-index-$id", ExistingWorkPolicy.KEEP, request
                    )
                }.onFailure {
                    indexingQueueFailures++
                }
            }
            return RestoreResult(restored, skipped, restoredBytes, indexingQueueFailures)
        } catch (error: Exception) {
            // If validation or the transactional database insert fails, do not leave staged copies behind.
            if (!databaseCommitted) finalFiles.forEach { it.delete() }
            throw error
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private fun openDocument(document: DocumentEntity): InputStream {
        if (!document.managedPath.isNullOrBlank()) {
            val file = File(document.managedPath)
            if (file.isFile) return file.inputStream()
        }
        return resolver.openInputStream(Uri.parse(document.sourceUri))
            ?: throw IllegalStateException("Source unavailable: ${document.displayName}. Re-import it before backing up.")
    }

    private fun copyAndHash(
        input: InputStream,
        output: ZipOutputStream,
        digest: MessageDigest,
        maxBytes: Long
    ): Pair<Long, String> {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "A document exceeds the backup size limit." }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyBounded(input: InputStream, destination: File, maxBytes: Long): Long {
        var total = 0L
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "A document exceeds the restore size limit." }
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Backup manifest is too large." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    data class BackupResult(val documentCount: Int, val documentBytes: Long, val archiveBytes: Long)
    data class RestoreResult(
        val restored: Int,
        val skipped: Int,
        val restoredBytes: Long,
        val indexingQueueFailures: Int = 0
    )

    companion object {
        const val FORMAT = "SHABANA_FILEKHOJ_BACKUP"
        const val MANIFEST = "filekhoj-manifest.json"
        private const val MIN_SUPPORTED_VERSION = 1
        private const val VERSION = 3
        private const val MAX_DOCUMENTS = 10_000
        private const val MAX_DISPLAY_NAME_LENGTH = 512
        private const val MAX_PROFILE_NAME_LENGTH = 40
        private const val MAX_REMINDER_LABEL_LENGTH = 80
        private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        private const val MAX_DOCUMENT_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOTAL_DOCUMENT_BYTES = 1024L * 1024L * 1024L
        private val DOCUMENT_ENTRY_PATTERN = Regex("documents/[0-9]+\\.bin")
    }
}
