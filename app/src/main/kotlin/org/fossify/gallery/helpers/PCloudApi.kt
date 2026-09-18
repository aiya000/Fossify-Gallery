package org.fossify.gallery.helpers

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

// The pCloud HTTP API, as much of it as the gallery needs. Every call goes to the host that came
// with the token, answers JSON, and reports failure in a "result" field rather than in the status
// code. Reads take the file or folder id the scanner stored; writes take the remote path, so
// that a name pCloud already knows can be acted on without a cache row for it
object PCloudApi {
    // events per diff page; the sync reads pages until one comes back short
    const val DIFF_PAGE_SIZE = 1000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // an upload is not done until the whole body went out
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    // blocks, so call it off the main thread. Throws PCloudException when pCloud itself refused,
    // and an IOException when the request never got there
    fun call(apiHost: String, accessToken: String, method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val body = client.newCall(buildRequest(apiHost, accessToken, method, params)).execute().use { it.body.string() }
        val json = JSONObject(body)
        val result = json.optInt("result", -1)
        if (result != 0) {
            throw PCloudException(result, json.optString("error"))
        }

        return json
    }

    // Like call(), but the answer is streamed instead of held as one string: listing a whole
    // account recursively runs to megabytes. onValue is handed every top-level key except
    // "result" and "error" and has to consume that key's value from the reader. When pCloud
    // refused, the body carries no data keys and the exception is thrown once it ends
    fun stream(apiHost: String, accessToken: String, method: String, params: Map<String, String> = emptyMap(), onValue: (name: String, reader: JsonReader) -> Unit) {
        client.newCall(buildRequest(apiHost, accessToken, method, params)).execute().use { response ->
            JsonReader(response.body.charStream()).use { reader ->
                var result = -1
                var error = ""
                reader.beginObject()
                while (reader.hasNext()) {
                    when (val name = reader.nextName()) {
                        "result" -> result = reader.nextInt()
                        "error" -> error = reader.nextString()
                        else -> onValue(name, reader)
                    }
                }
                reader.endObject()

                if (result != 0) {
                    throw PCloudException(result, error)
                }
            }
        }
    }

    fun userInfo(apiHost: String, accessToken: String) = call(apiHost, accessToken, "userinfo")

    // one event of a diff answer, trimmed to what the sync needs: what happened, to which file
    // or folder id, under which name in which folder. The scanner classifies the name and
    // pCloud's category into a media type like it does for a listing
    class DiffEntry(val event: String, val isFolder: Boolean, val itemId: Long, val name: String, val parentFolderId: Long, val category: Int)

    // diffId is the id to continue from next time, also when entries is empty
    class DiffPage(val diffId: Long, val entries: List<DiffEntry>)

    // The changes to the account since sinceDiffId, oldest first, at most limit of them; the
    // page's diffId is where to continue when it is full. With last instead of sinceDiffId the
    // newest events come back, which with last = 1 is the cheap way to learn the current diff
    // id before a full scan. Streamed like a listing is, a backlog of events can run long
    fun diff(apiHost: String, accessToken: String, sinceDiffId: Long?, last: Int? = null, limit: Int = DIFF_PAGE_SIZE): DiffPage {
        val params = HashMap<String, String>()
        sinceDiffId?.let { params["diffid"] = it.toString() }
        last?.let { params["last"] = it.toString() }
        params["limit"] = limit.toString()

        var diffId = 0L
        val entries = ArrayList<DiffEntry>()
        stream(apiHost, accessToken, "diff", params) { name, reader ->
            when (name) {
                "diffid" -> diffId = reader.nextLong()
                "entries" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        entries.add(readDiffEntry(reader))
                    }
                    reader.endArray()
                }

                else -> reader.skipValue()
            }
        }

        return DiffPage(diffId, entries)
    }

    private fun readDiffEntry(reader: JsonReader): DiffEntry {
        var event = ""
        var isFolder = false
        var itemId = 0L
        var name = ""
        var parentFolderId = -1L
        var category = 0

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "event" -> event = reader.nextString()
                // the share and account events carry no metadata object, or none of these keys
                "metadata" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "isfolder" -> isFolder = reader.nextBoolean()
                            "folderid", "fileid" -> itemId = reader.nextLong()
                            "name" -> name = reader.nextString()
                            "parentfolderid" -> parentFolderId = reader.nextLong()
                            "category" -> category = reader.nextInt()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                } else {
                    reader.skipValue()
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return DiffEntry(event, isFolder, itemId, name, parentFolderId, category)
    }

    // a short-lived https URL for a thumbnail of the file, "WIDTHxHEIGHT" sized, jpeg unless the
    // image is transparent. Only files whose metadata says thumb=true have one
    fun getThumbLink(apiHost: String, accessToken: String, fileId: Long, size: String): String {
        val json = call(apiHost, accessToken, "getthumblink", mapOf("fileid" to fileId.toString(), "size" to size))
        return toLink(json)
    }

    // The thumbnail bytes themselves rather than a link to them: one round trip per tile instead
    // of getthumblink plus a download. pCloud renders images and videos alike into a
    // "WIDTHxHEIGHT" box, each side between 16 and 2048 in multiples of 4, and fits the picture
    // inside it without cropping. Only files whose metadata says thumb=true have one. Handed
    // back as a Call so that a load Glide gave up on can be cancelled; read it with contentOf()
    fun thumbCall(apiHost: String, accessToken: String, fileId: Long, size: String): Call {
        return client.newCall(buildRequest(apiHost, accessToken, "getthumb", mapOf("fileid" to fileId.toString(), "size" to size)))
    }

    // The body of an answer to thumbCall(). A binary method reports a refusal the same way as
    // the JSON ones do, only with a JSON body where the picture was expected, so that case is
    // read and thrown. Closing the response closes the stream
    fun contentOf(response: Response): InputStream {
        val body = response.body
        val contentType = body.contentType()
        if (contentType?.type == "application" && contentType.subtype == "json") {
            val json = JSONObject(body.use { it.string() })
            throw PCloudException(json.optInt("result", -1), json.optString("error"))
        }

        if (!response.isSuccessful) {
            body.close()
            throw IOException("pCloud answered HTTP ${response.code}")
        }

        return body.byteStream()
    }

    // a short-lived https URL for the file's content
    fun getFileLink(apiHost: String, accessToken: String, fileId: Long): String {
        val json = call(apiHost, accessToken, "getfilelink", mapOf("fileid" to fileId.toString()))
        return toLink(json)
    }

    // A plain GET of a link handed out by getFileLink(): the link carries its own authorisation,
    // so no token goes with it. Handed back as a Call so that the caller can cancel it
    fun download(url: String): Call = client.newCall(Request.Builder().url(url).build())

    // moves the file into pCloud's own trash, from where the web UI can bring it back
    fun deleteFile(apiHost: String, accessToken: String, remotePath: String) {
        call(apiHost, accessToken, "deletefile", mapOf("path" to remotePath))
    }

    // the folder and everything under it go to pCloud's trash together
    fun deleteFolderRecursive(apiHost: String, accessToken: String, remotePath: String) {
        call(apiHost, accessToken, "deletefolderrecursive", mapOf("path" to remotePath))
    }

    // a new name in the same folder; the file keeps its id and its content hash
    fun renameFile(apiHost: String, accessToken: String, remotePath: String, newName: String) {
        call(apiHost, accessToken, "renamefile", mapOf("path" to remotePath, "toname" to newName))
    }

    fun renameFolder(apiHost: String, accessToken: String, remotePath: String, newName: String) {
        call(apiHost, accessToken, "renamefolder", mapOf("path" to remotePath, "toname" to newName))
    }

    // Creates a folder inside the one with the given id (0 is the root) and answers with the
    // new folder's id. pCloud refuses a name that is already taken there
    fun createFolder(apiHost: String, accessToken: String, parentFolderId: Long, name: String): Long {
        val json = call(apiHost, accessToken, "createfolder", mapOf("folderid" to parentFolderId.toString(), "name" to name))
        return json.getJSONObject("metadata").getLong("folderid")
    }

    // a copy of the file into the folder with the given id, under the same name; a name that
    // is taken there gets a number appended by pCloud rather than being overwritten
    fun copyFileTo(apiHost: String, accessToken: String, remotePath: String, toFolderId: Long) {
        call(apiHost, accessToken, "copyfile", mapOf("path" to remotePath, "tofolderid" to toFolderId.toString(), "toname" to remotePath.substringAfterLast('/'), "noover" to "1"))
    }

    // renamefile with a destination folder moves the file; it keeps its id and content hash
    fun moveFileTo(apiHost: String, accessToken: String, remotePath: String, toFolderId: Long) {
        call(apiHost, accessToken, "renamefile", mapOf("path" to remotePath, "tofolderid" to toFolderId.toString(), "toname" to remotePath.substringAfterLast('/')))
    }

    // Uploads one file into the folder with the given id as a multipart POST; the body is
    // streamed, so a video does not have to fit in memory. A name that is taken there gets
    // a number appended by pCloud rather than being overwritten, and nopartial keeps a file
    // whose upload broke off from appearing at all. mtime keeps the file's modification time
    fun upload(apiHost: String, accessToken: String, toFolderId: Long, name: String, body: RequestBody, modifiedSeconds: Long) {
        val params = mapOf(
            "folderid" to toFolderId.toString(),
            "filename" to name,
            "nopartial" to "1",
            "renameifexists" to "1",
            "mtime" to modifiedSeconds.toString()
        )
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body)
            .build()
        val request = buildRequest(apiHost, accessToken, "uploadfile", params)
            .newBuilder()
            .post(multipart)
            .build()

        val responseBody = client.newCall(request).execute().use { it.body.string() }
        val json = JSONObject(responseBody)
        val result = json.optInt("result", -1)
        if (result != 0) {
            throw PCloudException(result, json.optString("error"))
        }
    }

    // link answers carry a list of hosts and a path, any host serves the path
    private fun toLink(json: JSONObject): String {
        val host = json.getJSONArray("hosts").getString(0)
        return "https://$host${json.getString("path")}"
    }

    private fun buildRequest(apiHost: String, accessToken: String, method: String, params: Map<String, String>): Request {
        val url = "https://$apiHost/$method".toHttpUrl()
            .newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
    }
}

class PCloudException(val result: Int, val error: String) : Exception("pCloud returned $result: $error") {
    // an implicit grant token carries no expiry and pCloud sends no notice when it stops working,
    // so these two results are the only sign that the account has to be signed in again
    val requiresLogIn: Boolean
        get() = result == PCLOUD_RESULT_LOG_IN_FAILED || result == PCLOUD_RESULT_LOG_IN_REQUIRED
}
