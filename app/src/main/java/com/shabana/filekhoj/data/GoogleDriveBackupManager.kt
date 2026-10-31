package com.shabana.filekhoj.data

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive transport for FileKhoj's canonical ZIP archive.
 * Uses the same narrow drive.file scope, app-owned backup folder and resumable upload pattern
 * used by Shabana ERP. ZIP creation and restore validation remain in BackupRestoreManager.
 */
class GoogleDriveBackupManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    fun signedInEmail(): String? = GoogleSignIn.getLastSignedInAccount(context)?.email

    private fun accessToken(): String {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("Connect a Google account first.")
        val androidAccount: Account = account.account
            ?: throw IllegalStateException("Google account is missing. Please reconnect Drive.")
        return try {
            GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_FILE_SCOPE")
        } catch (error: UserRecoverableAuthException) {
            throw IllegalStateException("Google Drive permission needs attention. Disconnect and reconnect your account.", error)
        }
    }

    fun uploadLatestBackup(zipFile: File): String {
        require(zipFile.isFile && zipFile.length() > 0L) { "Backup ZIP is empty or missing." }
        val token = accessToken()
        val folderId = getOrCreateFolder(token)
        // Upload first; retain the previous good backup until the new archive is confirmed.
        val metadata = JSONObject()
            .put("name", BACKUP_NAME)
            .put("mimeType", "application/zip")
            .put("parents", JSONArray().put(folderId))
            .toString()
            .toRequestBody(JSON_TYPE)
        val init = Request.Builder()
            .url("$UPLOAD_ENDPOINT?uploadType=resumable&fields=id,name,size,modifiedTime")
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "application/zip")
            .header("X-Upload-Content-Length", zipFile.length().toString())
            .post(metadata)
            .build()
        val uploadUrl = client.newCall(init).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Drive upload initialization failed (HTTP ${response.code}).")
            response.header("Location") ?: throw IOException("Google Drive did not return an upload session.")
        }

        val body = object : RequestBody() {
            override fun contentType() = ZIP_TYPE
            override fun contentLength() = zipFile.length()
            override fun writeTo(sink: okio.BufferedSink) {
                zipFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                    }
                }
            }
        }
        val upload = Request.Builder()
            .url(uploadUrl)
            .header("Content-Range", "bytes 0-${zipFile.length() - 1}/${zipFile.length()}")
            .put(body)
            .build()
        val uploadedId = client.newCall(upload).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google Drive upload failed (HTTP ${response.code}).")
            JSONObject(response.body?.string().orEmpty()).optString("id")
                .ifBlank { throw IOException("Google Drive upload completed without a file ID.") }
        }
        // Only rotate old copies after the new archive has been confirmed by Drive.
        listFiles(token, "name = '$BACKUP_NAME' and '$folderId' in parents and trashed = false")
            .filter { it.optString("id") != uploadedId }
            .forEach { deleteFile(token, it.optString("id")) }
        return uploadedId
    }

    fun downloadLatestBackup(destination: File): String {
        val token = accessToken()
        val folderId = getOrCreateFolder(token)
        val latest = listFiles(token, "'$folderId' in parents and trashed = false")
            .filter { it.optString("name") == BACKUP_NAME }
            .maxByOrNull { it.optString("modifiedTime") }
            ?: throw IllegalStateException("No FileKhoj backup was found in your Google Drive folder.")
        val id = latest.optString("id").takeIf(String::isNotBlank)
            ?: throw IOException("Drive backup is missing its file ID.")
        val request = Request.Builder()
            .url("$FILES_ENDPOINT/${encodePath(id)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google Drive download failed (HTTP ${response.code}).")
            val temp = File(destination.parentFile, destination.name + ".tmp")
            try {
                response.body?.byteStream()?.use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: throw IOException("Google Drive returned an empty response.")
                if (!temp.isFile || temp.length() <= 0L) throw IOException("Downloaded backup is empty.")
                if (destination.exists() && !destination.delete()) throw IOException("Could not replace temporary backup.")
                check(temp.renameTo(destination)) { "Could not finalize downloaded backup." }
            } catch (error: Exception) {
                temp.delete()
                throw error
            }
        }
        return latest.optString("modifiedTime", "Drive backup")
    }

    private fun getOrCreateFolder(token: String): String {
        val escapedName = BACKUP_FOLDER.replace("'", "\\'")
        val query = "name = '$escapedName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        listFiles(token, query).firstOrNull()?.optString("id")?.takeIf(String::isNotBlank)?.let { return it }
        val body = JSONObject()
            .put("name", BACKUP_FOLDER)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url("$FILES_ENDPOINT?fields=id,name")
            .header("Authorization", "Bearer $token")
            .post(body).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Could not create FileKhoj Drive folder (HTTP ${response.code}).")
            JSONObject(response.body?.string().orEmpty()).optString("id")
                .ifBlank { throw IOException("Drive folder creation returned no ID.") }
        }
    }

    private fun listFiles(token: String, query: String): List<JSONObject> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$FILES_ENDPOINT?q=$encoded&fields=files(id,name,mimeType,modifiedTime,size)&pageSize=100")
            .header("Authorization", "Bearer $token")
            .get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google Drive listing failed (HTTP ${response.code}).")
            val files = JSONObject(response.body?.string().orEmpty()).optJSONArray("files") ?: JSONArray()
            (0 until files.length()).map { files.getJSONObject(it) }
        }
    }

    private fun deleteFile(token: String, id: String) {
        if (id.isBlank()) return
        val request = Request.Builder()
            .url("$FILES_ENDPOINT/${encodePath(id)}")
            .header("Authorization", "Bearer $token").delete().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IOException("Could not replace previous Drive backup (HTTP ${response.code}).")
            }
        }
    }

    private fun encodePath(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val BACKUP_FOLDER = "ShabanaFileKhoj_Backups"
        const val BACKUP_NAME = "Shabana_FileKhoj_Full_Backup.zip"
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val ZIP_TYPE = "application/zip".toMediaType()
    }
}
