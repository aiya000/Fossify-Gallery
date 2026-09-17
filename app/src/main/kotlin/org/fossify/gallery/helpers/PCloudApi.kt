package org.fossify.gallery.helpers

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// The pCloud HTTP API, as much of it as logging in needs. Every call goes to the host that came
// with the token, answers JSON, and reports failure in a "result" field rather than in the status
// code. Browsing and writing land here in a later step
object PCloudApi {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // blocks, so call it off the main thread. Throws PCloudException when pCloud itself refused,
    // and an IOException when the request never got there
    fun call(apiHost: String, accessToken: String, method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = "https://$apiHost/$method".toHttpUrl()
            .newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val body = client.newCall(request).execute().use { it.body.string() }
        val json = JSONObject(body)
        val result = json.optInt("result", -1)
        if (result != 0) {
            throw PCloudException(result, json.optString("error"))
        }

        return json
    }

    fun userInfo(apiHost: String, accessToken: String) = call(apiHost, accessToken, "userinfo")
}

class PCloudException(val result: Int, val error: String) : Exception("pCloud returned $result: $error") {
    // an implicit grant token carries no expiry and pCloud sends no notice when it stops working,
    // so these two results are the only sign that the account has to be signed in again
    val requiresLogIn: Boolean
        get() = result == PCLOUD_RESULT_LOG_IN_FAILED || result == PCLOUD_RESULT_LOG_IN_REQUIRED
}
