package com.puneet.friday

import android.app.Activity
import android.os.Bundle
import com.puneet.friday.ui.AppUi
import com.puneet.friday.ui.FridayDialog
import com.puneet.friday.ui.SettingsGroup
import com.puneet.friday.ui.SettingsRow
import java.io.IOException

/** License texts load only after the owner opens this page. */
class LicensesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (screen, content) = AppUi.groupedContent(this, "Open-source licenses")
        val notices = listOf(
            Triple("Geist", "SIL Open Font License", "licenses/geist.txt"),
            Triple("AndroidX Credential Manager", "Apache 2.0", "licenses/apache-2.0.txt"),
            Triple("Kotlin", "Apache 2.0", "licenses/apache-2.0.txt"),
            Triple("Google Identity", "Android SDK License", "licenses/google-identity.txt"),
            Triple("Untitled UI icons", "Untitled UI License", "licenses/untitled-ui.txt"),
        ) + DebugTools.licenseAssets().map { (name, file) -> Triple(name, "Third-party notices", file) }
        val group = SettingsGroup(this)
        for ((name, license, file) in notices) group.addRow(SettingsRow(this, name) {
            val text = try { assets.open(file).bufferedReader().use { it.readText() } }
                catch (_: IOException) { "Couldn't open this license. Try again." }
            FridayDialog.show(this, name, text, positive = "Close")
        }.apply { setValue(license) })
        content.addView(group, AppUi.params(this, 16))
        content.addView(AppUi.text(this, "Friday for Android ${BuildConfig.VERSION_NAME}", 12f, secondary = true),
            AppUi.params(this, 0))
        AppUi.applyPageInsets(screen)
        setContentView(screen)
    }
}
