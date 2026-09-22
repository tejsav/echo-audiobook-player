package com.echo.player.drive

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

internal class DriveException(message: String) : Exception(message)

/**
 * Reads publicly shared folders through the Drive API with an API key. No account, no sign-in:
 * only folders shared as "Anyone with the link" are visible.
 */
internal class DriveApi(private val context: Context, private val key: String) {

    /** Lets the key be restricted to this app in Google's console. */
    private val identity: Map<String, String> by lazy {
        buildMap {
            put("X-Android-Package", context.packageName)
            certSha1()?.let { put("X-Android-Cert", it) }
        }
    }

    fun headers(fileId: String, resourceKey: String?): Map<String, String> =
        if (resourceKey.isNullOrEmpty()) identity else identity + (RESOURCE_KEYS to "$fileId/$resourceKey")

    fun mediaUrl(fileId: String): String =
        BASE + "files/" + fileId + "?alt=media&supportsAllDrives=true&key=" + enc(key)

    fun readRoot(ref: FolderRef): DriveFolder {
        val meta = getJson(
            "files/" + ref.id + "?fields=id,name,mimeType,resourceKey&supportsAllDrives=true",
            headers(ref.id, ref.resourceKey)
        )
        if (meta.optString("mimeType") != FOLDER_MIME) {
            throw DriveException("That link opens a file, not a folder.")
        }
        return readFolder(
            id = ref.id,
            name = meta.optString("name").ifEmpty { "Drive folder" },
            resourceKey = ref.resourceKey ?: meta.stringOrNull("resourceKey"),
            depth = 0
        )
    }

    private fun readFolder(id: String, name: String, resourceKey: String?, depth: Int): DriveFolder {
        val files = ArrayList<DriveItem>()
        val folders = ArrayList<DriveFolder>()
        for (child in children(id, resourceKey)) {
            val mime = child.optString("mimeType")
            val childName = child.optString("name")
            val folderShortcut = child.optJSONObject("shortcutDetails")
                ?.takeIf { mime == SHORTCUT_MIME && it.optString("targetMimeType") == FOLDER_MIME }
            when {
                mime == FOLDER_MIME -> if (depth < MAX_DEPTH) {
                    folders += readFolder(child.getString("id"), childName, child.stringOrNull("resourceKey"), depth + 1)
                }
                // Lets a catalog list a book that lives somewhere else in Drive.
                folderShortcut != null -> if (depth < MAX_DEPTH) {
                    folders += readFolder(
                        folderShortcut.getString("targetId"),
                        childName,
                        folderShortcut.stringOrNull("targetResourceKey"),
                        depth + 1
                    )
                }
                // ponytail: Docs, Sheets and shortcuts to single files are skipped. Resolve file
                // shortcuts with one more request each if catalogs start using them.
                mime.startsWith("application/vnd.google-apps.") -> Unit
                else -> files += DriveItem(
                    id = child.getString("id"),
                    name = childName,
                    mimeType = mime,
                    size = child.optString("size").toLongOrNull(),
                    resourceKey = child.stringOrNull("resourceKey")
                )
            }
        }
        return DriveFolder(id, name, resourceKey, files, folders)
    }

    private fun children(folderId: String, resourceKey: String?): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        val query = enc("'$folderId' in parents and trashed = false")
        var token: String? = null
        do {
            val page = getJson(
                "files?q=" + query + "&fields=" + enc(LIST_FIELDS) + "&pageSize=1000" +
                    "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                    (token?.let { "&pageToken=" + enc(it) } ?: ""),
                headers(folderId, resourceKey)
            )
            val items = page.optJSONArray("files")
            if (items != null) for (i in 0 until items.length()) items.optJSONObject(i)?.let(found::add)
            token = page.stringOrNull("nextPageToken")
        } while (token != null)
        return found
    }

    private fun getJson(path: String, headers: Map<String, String>): JSONObject {
        val connection = URL(BASE + path + "&key=" + enc(key)).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        try {
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw DriveException("Could not reach Google Drive. Check the connection.")
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = runCatching { stream?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty()
            if (code !in 200..299) throw DriveException(explain(code, body))
            return runCatching { JSONObject(body) }
                .getOrElse { throw DriveException("Google Drive sent an answer ECHO could not read.") }
        } finally {
            connection.disconnect()
        }
    }

    private fun explain(code: Int, body: String): String {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val message = error?.optString("message").orEmpty()
        return when {
            code == 404 ->
                "ECHO can't see that folder. Check the link, and that it is shared as \"Anyone with the link\"."
            reason == "keyInvalid" || message.contains("API key not valid", ignoreCase = true) ->
                "This build's Google Drive key is not valid."
            reason == "accessNotConfigured" || message.contains("has not been used", ignoreCase = true) ->
                "The Google Drive API is not turned on for this build's key."
            message.contains("blocked", ignoreCase = true) ->
                "Google blocked this build's key. Its Android restriction may not match this app."
            reason.contains("rateLimit", ignoreCase = true) || reason.contains("quota", ignoreCase = true) ->
                "Google Drive is limiting requests. Try again in a little while."
            else -> "Google Drive refused (" + code + ")" + (if (message.isNotEmpty()) ": $message" else ".")
        }
    }

    private fun certSha1(): String? = runCatching {
        signature()?.let { sig ->
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signature(): Signature? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifEmpty { null }

    private companion object {
        const val BASE = "https://www.googleapis.com/drive/v3/"
        const val RESOURCE_KEYS = "X-Goog-Drive-Resource-Keys"
        const val MAX_DEPTH = 5
        const val LIST_FIELDS =
            "nextPageToken,files(id,name,mimeType,size,resourceKey," +
                "shortcutDetails(targetId,targetMimeType,targetResourceKey))"
    }
}
