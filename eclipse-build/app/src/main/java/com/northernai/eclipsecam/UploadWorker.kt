package com.northernai.eclipsecam

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * Uploads one captured file to Drive. Runs under WorkManager so an upload survives the app being
 * closed, waits for a network instead of failing on one, and backs off rather than hammering.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
            ?: return@withContext Result.failure(reason("No file to upload"))
        val name = inputData.getString(KEY_NAME) ?: uri.lastPathSegment ?: "photo.jpg"
        val mime = inputData.getString(KEY_MIME) ?: "image/jpeg"

        try {
            val token = Drive.accessToken(applicationContext)
            val folder = Drive.ensureFolder(token)
            val id = Drive.upload(applicationContext.contentResolver, token, folder, uri, name, mime)
            Result.success(workDataOf(KEY_NAME to name, KEY_FILE_ID to id))
        } catch (e: Drive.NeedsConsent) {
            // Retrying cannot produce consent — the user has to tap Connect again.
            Result.failure(reason("Drive access needs to be granted again"))
        } catch (e: Drive.PermanentDriveError) {
            Result.failure(reason(e.message ?: "Drive rejected the upload"))
        } catch (e: FileNotFoundException) {
            Result.failure(reason("$name was moved or deleted before it could upload"))
        } catch (e: Exception) {
            // Network blips, 5xx, timeouts: worth another go.
            if (runAttemptCount >= MAX_ATTEMPTS) {
                Result.failure(reason("Gave up on $name after $MAX_ATTEMPTS tries: ${e.message}"))
            } else {
                Result.retry()
            }
        }
    }

    private fun reason(message: String) = workDataOf(KEY_ERROR to message)

    companion object {
        const val TAG = "drive-upload"
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        const val KEY_FILE_ID = "fileId"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, uri: Uri, name: String, mime: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_URI, uri.toString())
                        .putString(KEY_NAME, name)
                        .putString(KEY_MIME, mime)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            // Keyed on the file so a retry or a double-tap cannot upload the same shot twice.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload:$uri", ExistingWorkPolicy.KEEP, request)
        }
    }
}
