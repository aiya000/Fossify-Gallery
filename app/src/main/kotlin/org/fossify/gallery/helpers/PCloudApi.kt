package org.fossify.gallery.helpers

import android.util.JsonReader
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// The pCloud HTTP API, as much of it as the gallery needs. Every call goes to the host that came
// with the token, answers JSON, and reports failure in a "result" field rather than in the status
// code. Writing lands here in a later step
object PCloudApi {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
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

    // a short-lived https URL for a thumbnail of the file, "WIDTHxHEIGHT" sized, jpeg unless the
    // image is transparent. Only files whose metadata says thumb=true have one
    fun getThumbLink(apiHost: String, accessToken: String, fileId: Long, size: String): String {
        val json = call(apiHost, accessToken, "getthumblink", mapOf("fileid" to fileId.toString(), "size" to size))
        return toLink(json)
    }

    // a short-lived https URL for the file's content
    fun getFileLink(apiHost: String, accessToken: String, fileId: Long): String {
        val json = call(apiHost, accessToken, "getfilelink", mapOf("fileid" to fileId.toString()))
        return toLink(json)
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
