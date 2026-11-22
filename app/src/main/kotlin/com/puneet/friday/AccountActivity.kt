package com.puneet.friday

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.puneet.friday.auth.AccountClient
import com.puneet.friday.auth.AccountStore
import com.puneet.friday.auth.SignInRequired
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow
import java.util.concurrent.CompletableFuture

class AccountActivity : Activity() {
    private enum class Deletion { DONE, EXPIRED, FAILED }

    private lateinit var signOut: SettingsRow
    private lateinit var delete: SettingsRow
    private lateinit var status: TextView
    private var dialog: Dialog? = null
    private var deletion: CompletableFuture<Deletion>? = null
    private val blockBack = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback {} else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (screen, content) = AppUi.groupedContent(this, "Account", onBack = ::goBack)
        @Suppress("UNCHECKED_CAST")
        val pending = lastNonConfigurationInstance as? CompletableFuture<Deletion>
        val account = AccountStore(this).session()
        if (account != null) {
            val profile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(AppUi.sectionLabel(context, "Google account"), AppUi.params(context, 8))
                if (account.displayName.isNotBlank() && account.displayName != account.email) {
                    addView(AppUi.text(context, account.displayName, 16f, bold = true),
                        AppUi.params(context, if (account.email.isBlank()) 0 else 4))
                }
                if (account.email.isNotBlank() || account.displayName.isBlank()) {
                    addView(AppUi.text(context, account.email.ifBlank { "Signed in" }, 14f, secondary = true),
                        AppUi.params(context, 0))
                }
            }
            val profileRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val horizontal = AppUi.dp(context, 12)
                val vertical = AppUi.dp(context, 16)
                setPadding(horizontal, vertical, horizontal, vertical)
                val iconSize = AppUi.dp(context, 20)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_google)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = horizontal })
                addView(profile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(SettingsGroup(this).apply { addRow(profileRow) }, AppUi.params(this, 16))
        }
        signOut = SettingsRow(this, "Sign out", showChevron = false, icon = R.drawable.ic_sign_out) { confirmSignOut() }
        delete = SettingsRow(this, "Delete Friday account", showChevron = false, icon = R.drawable.ic_delete_account) {
            account?.let { confirmDeletion(it.sessionId) }
        }
        content.addView(SettingsGroup(this).apply { addRow(signOut) }, AppUi.params(this, 16))
        content.addView(SettingsGroup(this).apply { addRow(delete) }, AppUi.params(this, 12))
        status = AppUi.text(this, "", 13f, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        content.addView(status, AppUi.params(this, 0))

        AppUi.applyPageInsets(screen)
        setContentView(screen)
        if (pending != null) observeDeletion(pending)
        else if (account == null) finish()
    }

    private fun confirmSignOut() {
        dialog = FridayDialog.show(
            this, "Sign out of Friday?",
            "Friday will stop listening on this phone. Sign in again to use voice commands.",
            positive = "Sign out", negative = "Cancel",
        ) {
            setResult(SIGN_OUT)
            finish()
        }
    }

    private fun confirmDeletion(sessionId: String) {
        dialog = FridayDialog.show(
            this, "Delete your Friday account?",
            "This removes your Friday account data and signs you out on all devices. You can still use your Google account.",
            positive = "Delete account", negative = "Cancel", destructive = true,
        ) {
            val context = applicationContext
            observeDeletion(CompletableFuture.supplyAsync {
                try {
                    AccountClient(context).deleteAccount(sessionId)
                    Deletion.DONE
                } catch (_: SignInRequired) {
                    Deletion.EXPIRED
                } catch (_: Exception) {
                    Deletion.FAILED
                }
            })
        }
    }

    private fun observeDeletion(request: CompletableFuture<Deletion>) {
        deletion = request
        if (Build.VERSION.SDK_INT >= 33 && blockBack != null) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, blockBack)
        }
        signOut.isEnabled = false
        delete.isEnabled = false
        showStatus("Deleting your Friday account…")
        request.thenAccept { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                deletion = null
                unblockBack()
                when (result) {
                    Deletion.DONE -> {
                        setResult(RESULT_OK)
                        finish()
                    }
                    Deletion.EXPIRED -> {
                        signOut.isEnabled = true
                        showStatus("Sign out and sign in again to delete your account.")
                    }
                    Deletion.FAILED -> {
                        signOut.isEnabled = true
                        delete.isEnabled = true
                        showStatus("Couldn't confirm deletion. Try again.")
                    }
                }
            }
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun goBack() {
        if (deletion == null) finish()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Used on Android versions before the back dispatcher")
    override fun onBackPressed() = goBack()

    override fun onRetainNonConfigurationInstance(): Any? = deletion

    private fun unblockBack() {
        if (Build.VERSION.SDK_INT >= 33 && blockBack != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(blockBack)
        }
    }

    override fun onDestroy() {
        if (deletion != null) unblockBack()
        dialog?.dismiss()
        super.onDestroy()
    }

    companion object {
        const val SIGN_OUT = RESULT_FIRST_USER
    }
}
