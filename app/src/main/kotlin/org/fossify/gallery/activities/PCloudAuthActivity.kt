package org.fossify.gallery.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.extensions.config
import org.fossify.gallery.helpers.PCLOUD_AUTHORIZE_URL
import org.fossify.gallery.helpers.PCLOUD_OAUTH_SCHEME
import org.fossify.gallery.helpers.PCloudApi
import java.util.UUID

// Signs in to pCloud with OAuth 2.0 implicit grant: the browser is handed the authorize page and
// pCloud hands the token straight back through the pcloud-oauth:// redirect, so this app never
// needs a client secret. The manifest holds the intent filter that catches the redirect.
//
// The activity is a trampoline with no layout of its own. It opens the browser on its first
// resume; coming back with a redirect means a token arrived, and coming back without one means
// the login was abandoned
class PCloudAuthActivity : SimpleActivity() {
    companion object {
        private const val WAS_BROWSER_OPENED = "was_browser_opened"
    }

    private var wasBrowserOpened = false
    private var isHandlingRedirect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wasBrowserOpened = savedInstanceState?.getBoolean(WAS_BROWSER_OPENED) == true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val redirect = intent?.data
        when {
            redirect != null -> handleRedirect(redirect)
            !wasBrowserOpened -> openAuthorizePage()
            else -> finishWith(RESULT_CANCELED)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(WAS_BROWSER_OPENED, wasBrowserOpened)
    }

    private fun openAuthorizePage() {
        if (BuildConfig.PCLOUD_CLIENT_ID.isEmpty()) {
            toast(R.string.pcloud_client_id_missing)
            finishWith(RESULT_CANCELED)
            return
        }

        // guards against a pcloud-oauth:// redirect fired by anything but this login attempt
        val state = UUID.randomUUID().toString()
        config.pCloudOAuthState = state

        val url = Uri.parse(PCLOUD_AUTHORIZE_URL)
            .buildUpon()
            .appendQueryParameter("client_id", BuildConfig.PCLOUD_CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("redirect_uri", getRedirectUri())
            .appendQueryParameter("state", state)
            .build()

        try {
            wasBrowserOpened = true
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: ActivityNotFoundException) {
            wasBrowserOpened = false
            toast(org.fossify.commons.R.string.no_app_found)
            finishWith(RESULT_CANCELED)
        }
    }

    // the debug build carries the ".debug" suffix, so both spellings have to be registered with
    // pCloud as redirect URIs
    private fun getRedirectUri() = "$PCLOUD_OAUTH_SCHEME://${BuildConfig.APPLICATION_ID}"

    private fun handleRedirect(redirect: Uri) {
        if (isHandlingRedirect) {
            return
        }

        isHandlingRedirect = true
        val expectedState = config.pCloudOAuthState
        config.pCloudOAuthState = ""

        // the token rides in the fragment so it never reaches a server, but read the query too in
        // case pCloud ever moves it
        val values = parseKeyValues(redirect.encodedQuery) + parseKeyValues(redirect.encodedFragment)
        val accessToken = values["access_token"].orEmpty()
        val apiHost = values["hostname"].orEmpty()

        when {
            expectedState.isEmpty() || values["state"] != expectedState -> failLogIn()
            accessToken.isEmpty() || apiHost.isEmpty() -> failLogIn()
            else -> storeAccount(accessToken, apiHost)
        }
    }

    private fun parseKeyValues(raw: String?) = raw.orEmpty()
        .split('&')
        .filter { it.contains('=') }
        .associate { Uri.decode(it.substringBefore('=')) to Uri.decode(it.substringAfter('=')) }

    // userinfo doubles as the proof that the token and the host actually work together, so
    // nothing is stored until it answers
    private fun storeAccount(accessToken: String, apiHost: String) {
        ensureBackgroundThread {
            try {
                val email = PCloudApi.userInfo(apiHost, accessToken).optString("email")
                runOnUiThread {
                    config.pCloudAccessToken = accessToken
                    config.pCloudApiHost = apiHost
                    config.pCloudAccountEmail = email
                    finishWith(RESULT_OK)
                }
            } catch (e: Exception) {
                runOnUiThread { failLogIn() }
            }
        }
    }

    private fun failLogIn() {
        toast(R.string.pcloud_log_in_failed)
        finishWith(RESULT_CANCELED)
    }

    private fun finishWith(result: Int) {
        setResult(result)
        finish()
    }
}
