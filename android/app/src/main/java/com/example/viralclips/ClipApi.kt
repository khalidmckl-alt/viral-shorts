package com.example.viralclips

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ClipInfo(
    val index: Int, val start: Double, val end: Double,
    val title: String, val reason: String, val score: Int,
)

data class JobStatus(
    val status: String, val stage: String, val progress: Int,
    val error: String?, val clips: List<ClipInfo>,
)

class ClipApi(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    private class UriBody(
        private val resolver: ContentResolver, private val uri: Uri, private val size: Long,
    ) : RequestBody() {
        override fun contentType(): MediaType? = "video/mp4".toMediaType()
        override fun contentLength(): Long = size
        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open video")
            input.use { sink.writeAll(it.source()) }
        }
    }

    suspend fun upload(baseUrl: String, uri: Uri, maxClips: Int): String =
        withContext(Dispatchers.IO) {
            val (name, size) = fileInfo(context, uri)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("max_clips", maxClips.toString())
                .addFormDataPart("file", name, UriBody(context.contentResolver, uri, size))
                .build()
            val req = Request.Builder().url("$baseUrl/jobs").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Upload failed (${resp.code})")
                JSONObject(resp.body!!.string()).getString("job_id")
            }
        }

    suspend fun status(baseUrl: String, jobId: String): JobStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$baseUrl/jobs/$jobId").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Status check failed (${resp.code})")
            val j = JSONObject(resp.body!!.string())
            val arr = j.getJSONArray("clips")
            val clips = (0 until arr.length()).map {
                val c = arr.getJSONObject(it)
                ClipInfo(
                    c.getInt("index"), c.getDouble("start"), c.getDouble("end"),
                    c.optString("title"), c.optString("reason"), c.optInt("score"),
                )
            }
            JobStatus(
                j.getString("status"), j.optString("stage"), j.optInt("progress"),
                if (j.isNull("error")) null else j.getString("error"), clips,
            )
        }
    }

    suspend fun download(baseUrl: String, jobId: String, index: Int): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "clips").apply { mkdirs() }
            val file = File(dir, "${jobId}_${index + 1}.mp4")
            val req = Request.Builder().url("$baseUrl/jobs/$jobId/clips/$index").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                file.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
            }
            file
        }
}

fun fileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "video.mp4"
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (n >= 0) name = c.getString(n) ?: name
            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
        }
    }
    return name to size
}
