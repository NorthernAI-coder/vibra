package com.northernai.eclipsecam

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Drive upload, done directly against the Drive REST API.
 *
 * Authorization goes through Play Services' AuthorizationClient, which keys off the app's package
 * name and signing certificate — so there is no client ID or `google-services.json` in this repo.
 * The one-time setup is in the Google Cloud console; see README.
 *
 * The scope is `drive.file`, the narrowest one that works: the app can only ever see and touch
 * files it created itself. It cannot read the rest of the user's Drive.
 */
object Drive {

    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "NorthernCam"

    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    /** Raised when the user must visibly grant or re-grant access; retrying in the background won't help. */
    class NeedsConsent(val result: AuthorizationResult) : IOException("Drive access needs to be granted")

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()

    /**
     * Blocking token fetch, for use off the main thread. When access was granted earlier this
     * returns a fresh token with no UI; otherwise it throws [NeedsConsent] so the caller can put
     * the user back in front of the consent screen rather than retrying forever.
     */
    @Throws(IOException::class)
    fun accessToken(context: Context): String {
        val task = Identity.getAuthorizationClient(context).authorize(authorizationRequest())
        val result = try {
            Tasks.await(task, 60, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            throw IOException("Could not reach Google authorization: ${t.message}", t)
        }
        if (result.hasResolution()) throw NeedsConsent(result)
        return result.accessToken ?: throw NeedsConsent(result)
    }

    /** Returns the id of the app's Drive folder, creating it the first time. */
    @Throws(IOException::class)
    fun ensureFolder(token: String): String {
        val query = "name = '${FOLDER_NAME.replace("'", "\\'")}' " +
            "and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = FILES.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()
        val found = http.newCall(Request.Builder().url(url).bearer(token).get().build())
            .execute().use { response ->
                val body = response.readOrThrow("folder lookup")
                JSONObject(body).optJSONArray("files")?.optJSONObject(0)?.optString("id")
            }
        if (!found.isNullOrEmpty()) return found

        val metadata = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", FOLDER_MIME)
            .toString()
            .toRequestBody(JSON)
        return http.newCall(
            Request.Builder()
                .url("$FILES?fields=id")
                .bearer(token)
                .post(metadata)
                .build()
        ).execute().use { response ->
            val body = response.readOrThrow("folder create")
            JSONObject(body).optString("id").ifEmpty { throw IOException("Drive returned no folder id") }
        }
    }

    /**
     * Uploads the content at [uri] into [folderId]. The bytes are streamed straight from the
     * MediaStore entry, so a 25 MB DNG never has to sit in memory.
     */
    @Throws(IOException::class)
    fun upload(
        resolver: ContentResolver,
        token: String,
        folderId: String,
        uri: Uri,
        name: String,
        mime: String
    ): String {
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .toString()
            .toRequestBody(JSON)
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata)
            .addPart(streamBody(resolver, uri, mime, length))
            .build()
        return http.newCall(
            Request.Builder()
                .url("$UPLOAD?uploadType=multipart&fields=id")
                .bearer(token)
                .post(body)
                .build()
        ).execute().use { response ->
            val text = response.readOrThrow("upload of $name")
            JSONObject(text).optString("id").ifEmpty { throw IOException("Drive returned no file id") }
        }
    }

    private fun streamBody(
        resolver: ContentResolver,
        uri: Uri,
        mime: String,
        length: Long
    ): RequestBody = object : RequestBody() {
        override fun contentType() = mime.toMediaTypeOrNull()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Could not open $uri — it may have been deleted")
            input.use { sink.writeAll(it.source()) }
        }
    }

    private val JSON = "application/json; charset=UTF-8".toMediaType()

    private fun Request.Builder.bearer(token: String) = addHeader("Authorization", "Bearer $token")

    /** Distinguishes "try again later" from "this will never work", so the worker can stop retrying. */
    private fun okhttp3.Response.readOrThrow(what: String): String {
        val text = body?.string().orEmpty()
        if (isSuccessful) return text
        val detail = runCatching {
            JSONObject(text).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        val message = "Drive rejected $what: HTTP $code${if (detail.isEmpty()) "" else " — $detail"}"
        throw if (code == 401 || code == 403) PermanentDriveError(message) else IOException(message)
    }

    /** A failure that retrying cannot fix — expired grant, revoked scope, Drive API not enabled. */
    class PermanentDriveError(message: String) : IOException(message)
}
