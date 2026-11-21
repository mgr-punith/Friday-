package com.puneet.friday.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.puneet.friday.R
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** Google owns the account picker. Friday receives an ID token and verifies it on the server. */
class SignInActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: AccountStore
    private lateinit var client: AccountClient
    private lateinit var button: Button
    private lateinit var browser: Button
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private var cancellation: CancellationSignal? = null
    private var busy = false
    private var completed = false
    private var generation = 0
    private var loginRevision: Long? = null
    private var waitingForBrowser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AccountStore(this)
        client = AccountClient(this)
        createSignInContent()
        handleCallback(intent)
    }

    private fun createSignInContent() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        page.addView(AppUi.backButton(this) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.8f))

        val signIn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = AppUi.surface(this@SignInActivity, 16, R.color.surface_muted, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        signIn.addView(mark, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(16) })
        signIn.addView(AppUi.text(this, "Sign in to Friday", 19f, bold = true).apply {
            gravity = Gravity.CENTER
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, AppUi.params(this, 24))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            background = AppUi.surface(this@SignInActivity, 24, R.color.surface_muted, null)
        }
        val accountRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        accountRow.addView(AppUi.text(this, "Google account", 13f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val info = AppUi.iconButton(this, R.drawable.ic_info_circle, "About Google sign-in") {
            FridayDialog.show(this, "Google sign-in", "Friday uses your Google identity to sign you in and apply account usage limits. Google shares your name and email. Friday doesn't request access to Gmail, Drive or your Google contacts.")
        }
        accountRow.addView(info, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(accountRow, AppUi.params(this, 8))
        button = AppUi.button(this, "Continue with Google", AppUi.ButtonTone.PRIMARY) { begin(false) }.apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 14f
            setTextColor(Color.rgb(31, 31, 31))
            background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.control_pressed)),
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(24).toFloat()
                }, null)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_google, 0, 0, 0)
            compoundDrawablePadding = AppUi.dp(this@SignInActivity, 12)
        }
        card.addView(button, AppUi.params(this, 8))
        browser = AppUi.button(this, "Use browser", AppUi.ButtonTone.QUIET) { begin(true) }
        card.addView(browser, AppUi.params(this, 0))

        val status = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Signing in"
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        status.addView(progress, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        message = AppUi.text(this, "", 12f, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        status.addView(message, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        signIn.addView(card, AppUi.params(this, 0))
        signIn.addView(status, AppUi.params(this, 0))
        page.addView(signIn, LinearLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(32), dp(360)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        page.addView(View(this), LinearLayout.LayoutParams(1, 0, 1.2f))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(getColor(R.color.screen_background))
            addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun dp(value: Int): Int = AppUi.dp(this, value)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // A browser callback may finish in a second instance after process recreation.
        if (store.session() != null) complete()
    }

    private fun begin(useBrowser: Boolean) {
        if (busy) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
            message.text = "Unlock your phone to sign in."
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
            return
        }
        setBusy(true, "Connecting…")
        cancellation?.cancel()
        waitingForBrowser = false
        val request = ++generation
        val revision = try { store.beginLogin() } catch (error: Exception) { fail(request, error); return }
        loginRevision = revision
        worker.execute {
            try {
                val login = client.begin(revision)
                current(request) { if (useBrowser) openBrowser(login) else pickAccount(login) }
            } catch (error: Exception) { fail(request, error) }
        }
    }

    private fun pickAccount(login: JSONObject) {
        try {
            val option = GetSignInWithGoogleOption.Builder(login.getString("googleWebClientId")).setNonce(login.getString("nonce")).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            cancellation = CancellationSignal()
            val revision = generation
            CredentialManager.create(this).getCredentialAsync(this, request, cancellation, java.util.concurrent.Executor { runOnUiThread(it) },
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        if (revision != generation || isDestroyed) return
                        try {
                            val credential = result.credential as? CustomCredential ?: throw IOException("invalid_google_response")
                            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw IOException("invalid_google_response")
                            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                            exchange(login, token)
                        } catch (error: Exception) { fail(revision, error) }
                    }
                    override fun onError(e: GetCredentialException) {
                        if (revision != generation || isDestroyed) return
                        if (e is GetCredentialProviderConfigurationException) openBrowser(login)
                        else if (e is GetCredentialCancellationException) {
                            loginRevision?.let(store::cancelLogin)
                            setBusy(false, "Sign-in cancelled.")
                        }
                        else if (e is NoCredentialException) {
                            setBusy(false, "No Google account found. Use your browser to sign in.")
                        }
                        else setBusy(false, "Google sign-in unavailable. Try again or use your browser.")
                    }
                })
        } catch (_: Exception) { openBrowser(login) }
    }

    private fun openBrowser(login: JSONObject) {
        val value = login.optString("browserUrl")
        val uri = Uri.parse(value)
        if (uri.scheme != "https" || uri.host != "accounts.google.com") {
            setBusy(false, "Browser sign-in isn't configured yet.")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            waitingForBrowser = true
            setBusy(false, "Finish signing in in your browser, then return to Friday.")
        } catch (_: RuntimeException) { setBusy(false, "No browser is available on this phone.") }
    }

    private fun handleCallback(intent: Intent) {
        val uri = intent.data ?: return
        intent.data = null
        val login = store.pendingLogin()
        if (!uri.isHierarchical || uri.encodedPath.orEmpty() !in listOf("", "/") || uri.fragment != null ||
            uri.getQueryParameters("loginId").size != 1 ||
            !AuthPolicy.acceptsCallback(uri.scheme, uri.host, uri.getQueryParameter("loginId"), login?.optString("loginId"))) {
            message.text = "That sign-in link has expired. Try again."
            return
        }
        loginRevision = login!!.optLong("revision")
        waitingForBrowser = false
        exchange(login, null)
    }

    private fun exchange(login: JSONObject, token: String?) {
        setBusy(true, "Signing in…")
        val request = ++generation
        worker.execute {
            try {
                client.finish(login, token)
                current(request) { complete() }
            } catch (error: Exception) { fail(request, error) }
        }
    }

    private fun fail(request: Int, error: Exception) = current(request) {
        setBusy(false, when {
            error is UnknownHostException -> "No internet. Connect and try again."
            error.message == "login_busy" -> "Too many sign-in attempts. Try again later."
            error.message == "login_expired" -> "Sign-in expired. Try again."
            error.message == "login_unavailable" -> "Friday's sign-in service is unavailable. Try again later."
            error is SignInRequired -> "Google couldn't verify this sign-in. Try again."
            else -> "Couldn't sign in. Try again."
        })
    }

    private fun current(request: Int, action: () -> Unit) = runOnUiThread {
        if (request == generation && !isFinishing && !isDestroyed) action()
    }
    private fun setBusy(value: Boolean, text: String) {
        busy = value; button.isEnabled = !value; browser.isEnabled = !value
        button.alpha = if (value) 0.55f else 1f
        browser.alpha = if (value) 0.55f else 1f
        progress.visibility = if (value) View.VISIBLE else View.GONE
        message.text = text
    }

    private fun complete() {
        completed = true
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        generation++
        cancellation?.cancel()
        worker.shutdownNow()
        if (!completed && (isFinishing || !waitingForBrowser)) loginRevision?.let(store::cancelLogin)
        super.onDestroy()
    }
}
