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
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, offline-first FileKhoj archive.
 * The archive contains document bytes and library metadata; it never contains PDF passwords.
 */
class BackupRestoreManager(context: Context) {
    private val app = context.applicationContext
    private val dao = DocumentDatabase.getInstance(app).documentDao()
    private val resolver = app.contentResolver

    suspend fun createBackup(destination: File): BackupResult {
        val documents = dao.getAllForBackup()
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
        val rows = JSONArray()
        var bytes = 0L
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            documents.forEachIndexed { index, doc ->
                val payloadName = "documents/$index.bin"
                val payload = openDocument(doc)
                zip.putNextEntry(ZipEntry(payloadName))
                payload.use { input -> bytes += input.copyTo(zip) }
                zip.closeEntry()
                rows.put(JSONObject()
                    .put("entry", payloadName)
                    .put("displayName", doc.displayName)
                    .put("mimeType", doc.mimeType)
                    .put("sizeBytes", doc.sizeBytes)
                    .put("dateAdded", doc.dateAdded)
                    .put("sourceLastModified", doc.sourceLastModified ?: JSONObject.NULL)
                    .put("category", doc.category ?: JSONObject.NULL)
                    .put("contentHash", doc.contentHash ?: JSONObject.NULL)
                    .put("isFavourite", doc.isFavourite)
                    .put("signatureStatus", doc.signatureStatus)
                    .put("signatureCount", doc.signatureCount)
                )
            }
            manifest.put("documents", rows)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return BackupResult(documents.size, bytes, destination.length())
    }

    suspend fun restoreBackup(source: File): RestoreResult {
        val entries = HashMap<String, ByteArray>()
        var manifest: JSONObject? = null
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.isDirectory -> Unit
                    entry.name == MANIFEST -> {
                        require(manifest == null) { "Backup contains duplicate manifests." }
                        manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    entry.name.startsWith("documents/") && !entry.name.contains("..") -> {
                        require(entry.name !in entries) { "Backup contains duplicate document entries." }
                        entries[entry.name] = zip.readBytes()
                    }
                    else -> throw IllegalArgumentException("Unexpected file in backup archive.")
                }
                zip.closeEntry()
            }
        }
        val root = manifest ?: throw IllegalArgumentException("This is not a FileKhoj backup.")
        require(root.optString("format") == FORMAT) { "Unsupported backup format." }
        val rows = root.optJSONArray("documents") ?: JSONArray()
        val restoreDir = File(app.filesDir, "documents/backup_restore").apply { mkdirs() }
        var restored = 0
        var skipped = 0
        var bytes = 0L
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val entry = row.getString("entry")
            val payload = entries[entry] ?: throw IllegalArgumentException("Backup is incomplete: missing document payload.")
            val safeName = row.optString("displayName", "Restored document")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Restored document" }
            val target = File(restoreDir, "${UUID.randomUUID()}_$safeName").canonicalFile
            require(target.parentFile == restoreDir.canonicalFile) { "Invalid document path in backup." }
            target.outputStream().buffered().use { it.write(payload) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
            val doc = DocumentEntity(
                displayName = safeName,
                mimeType = row.optString("mimeType", "application/octet-stream"),
                sizeBytes = payload.size.toLong(),
                sourceUri = uri.toString(),
                dateAdded = row.optLong("dateAdded", System.currentTimeMillis()),
                sourceLastModified = if (row.isNull("sourceLastModified")) null else row.optLong("sourceLastModified"),
                category = if (row.isNull("category")) null else row.optString("category"),
                contentHash = if (row.isNull("contentHash")) null else row.optString("contentHash"),
                signatureStatus = row.optString("signatureStatus", DocumentEntity.SIGNATURE_STATUS_UNKNOWN),
                signatureCount = row.optInt("signatureCount", 0),
                isStoredDecrypted = true,
                managedPath = target.absolutePath,
                isFavourite = row.optBoolean("isFavourite", false)
            )
            val id = dao.insert(doc)
            if (id == -1L) {
                target.delete()
                skipped++
            } else {
                bytes += payload.size
                restored++
                if (doc.isFavourite) dao.setFavourite(id, true)
                val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                    .setInputData(workDataOf(DocumentIndexWorker.KEY_DOCUMENT_ID to id))
                    .build()
                WorkManager.getInstance(app).enqueueUniqueWork(
                    "filekhoj-index-$id", ExistingWorkPolicy.KEEP, request
                )
            }
        }
        return RestoreResult(restored, skipped, bytes)
    }

    private fun openDocument(document: DocumentEntity): InputStream {
        if (document.isStoredDecrypted) {
            val file = document.managedPath?.let(::File)
                ?: throw IllegalStateException("Private copy missing: ${document.displayName}")
            require(file.isFile) { "Private copy unavailable: ${document.displayName}" }
            return file.inputStream()
        }
        return resolver.openInputStream(Uri.parse(document.sourceUri))
            ?: throw IllegalStateException("Source unavailable: ${document.displayName}. Re-import it before backing up.")
    }

    data class BackupResult(val documentCount: Int, val documentBytes: Long, val archiveBytes: Long)
    data class RestoreResult(val restored: Int, val skipped: Int, val restoredBytes: Long)

    companion object {
        const val FORMAT = "SHABANA_FILEKHOJ_BACKUP"
        const val MANIFEST = "filekhoj-manifest.json"
    }
}
