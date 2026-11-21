package com.puneet.friday.actions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.View
import java.util.Locale

/** Resolves names locally on a worker; runs Android effects only from the visible UI. */
class AndroidActionExecutor(private val activity: Activity) {
    sealed interface Preparation {
        /** Invoke on the UI thread after any user choice or confirmation. */
        data class Ready(val action: PhoneAction, val confirmationTarget: String? = null, val run: () -> ActionResult) : Preparation
        data class Choice(val action: PhoneAction, val question: String, val options: List<ChoiceOption>) : Preparation
        data class Finished(val result: ActionResult) : Preparation
    }

    data class ChoiceOption(val label: String, val ready: Preparation.Ready)

    /** Worker-safe. Reads local providers and builds intents; never starts an action. */
    fun prepare(action: PhoneAction): Preparation {
        if (DeviceLock.requiresUnlock(activity, action)) return Preparation.Finished(ActionResult.NeedsUnlock)
        action.validationError()?.let { return Preparation.Finished(ActionResult.Failed(it)) }
        return try {
            when (action) {
                is PhoneAction.CallContact -> prepareContact(action)
                is PhoneAction.OpenApp -> prepareApp(action)
                is PhoneAction.CallNumber -> Preparation.Ready(action, confirmationTarget = action.number) { runAction(action) }
                else -> Preparation.Ready(action) { runAction(action) }
            }
        } catch (_: SecurityException) {
            Preparation.Finished(permissionAfterFailure(action))
        } catch (_: RuntimeException) {
            Preparation.Finished(ActionResult.Failed("Try again"))
        }
    }

    /** Convenience for simple callers; UI should use prepare off-thread for name lookup. */
    fun execute(action: PhoneAction): ActionResult = when (val prepared = prepare(action)) {
        is Preparation.Ready -> prepared.run()
        is Preparation.Choice -> ActionResult.Failed(prepared.question)
        is Preparation.Finished -> prepared.result
    }

    private fun prepareContact(action: PhoneAction.CallContact): Preparation {
        callPermissionFailure(needsContacts = true)?.let { return Preparation.Finished(it) }
        val contacts = readContacts()
            ?: return Preparation.Finished(ActionResult.Failed("Contacts unavailable"))
        return when (val match = NameMatcher.matchContact(action.name, contacts.map { it.name })) {
            is MatchResult.Matched -> prepareContactNumbers(action, contacts[match.index])
            is MatchResult.Ambiguous -> {
                val matching = match.indices.map { contacts[it] }
                val duplicateNames = matching.groupingBy { NameMatcher.normalize(it.name) }.eachCount()
                val picks = matching.flatMap { contact ->
                    val numbers = uniqueNumbers(contact)
                    val preferred = preferredNumber(numbers)
                    val choices = if (preferred != null) listOf(preferred) else numbers
                    choices.map { contact to it }
                }.distinctBy { NameMatcher.normalize(it.first.name) to it.second.number }
                val options = picks.map { (contact, number) ->
                    val normalizedName = NameMatcher.normalize(contact.name)
                    val needsSuffix = duplicateNames[normalizedName] != 1 ||
                        picks.count { it.first === contact } > 1
                    val suffixCollision = picks.count {
                        NameMatcher.normalize(it.first.name) == normalizedName &&
                            it.second.number.takeLast(4) == number.number.takeLast(4)
                    } > 1
                    val label = when {
                        suffixCollision -> "${contact.name} · ${number.number}"
                        needsSuffix -> "${contact.name} · ending ${number.number.takeLast(4)}"
                        else -> contact.name
                    }
                    ChoiceOption(label, readyCall(action, number.number, contact.name))
                }
                Preparation.Choice(action, "Which contact?", options)
            }
            MatchResult.NotFound -> Preparation.Finished(ActionResult.Failed("I couldn't find that contact"))
        }
    }

    private fun prepareContactNumbers(action: PhoneAction.CallContact, contact: Contact): Preparation {
        val numbers = uniqueNumbers(contact)
        preferredNumber(numbers)?.let { return readyCall(action, it.number, contact.name) }
        return Preparation.Choice(
            action,
            "Which number for ${contact.name}?",
            numbers.map { number ->
                val suffix = number.number.takeLast(4)
                val label = if (numbers.count { it.number.takeLast(4) == suffix } == 1) {
                    "Ending $suffix"
                } else number.number
                ChoiceOption(label, readyCall(action, number.number, contact.name))
            },
        )
    }

    private fun uniqueNumbers(contact: Contact): List<ContactNumber> =
        contact.numbers.groupBy { it.number }.map { (number, entries) ->
            ContactNumber(number, entries.any { it.isDefault })
        }

    private fun preferredNumber(numbers: List<ContactNumber>): ContactNumber? =
        if (numbers.size == 1) numbers.single() else numbers.filter { it.isDefault }.singleOrNull()

    private fun readyCall(action: PhoneAction.CallContact, number: String, name: String): Preparation.Ready =
        Preparation.Ready(action, confirmationTarget = "$name · $number") {
            guarded(action) {
                callPermissionFailure(needsContacts = true) ?: placeCall(number, name)
            }
        }

    private fun prepareApp(action: PhoneAction.OpenApp): Preparation {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = activity.packageManager
        val apps = manager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .distinctBy { it.activityInfo.packageName }
            .map { LauncherApp(it.loadLabel(manager).toString(), it.activityInfo.packageName) }
        return when (val match = AppNameMatcher.match(action.name, apps)) {
            is MatchResult.Matched -> readyApp(action, apps[match.index])
            is MatchResult.Ambiguous -> {
                val matching = match.indices.map { apps[it] }
                val duplicateNames = matching.groupingBy { NameMatcher.normalize(it.label) }.eachCount()
                Preparation.Choice(
                    action,
                    "Which app?",
                    matching.map { app ->
                        val label = if (duplicateNames[NameMatcher.normalize(app.label)] != 1) {
                            "${app.label} · ${app.packageName}"
                        } else app.label
                        ChoiceOption(label, readyApp(action, app))
                    },
                )
            }
            MatchResult.NotFound -> Preparation.Finished(ActionResult.Failed("I couldn't find that app"))
        }
    }

    private fun readyApp(action: PhoneAction.OpenApp, app: LauncherApp): Preparation.Ready =
        Preparation.Ready(action) {
            guarded(action) {
                val intent = activity.packageManager.getLaunchIntentForPackage(app.packageName)
                if (intent == null) {
                    ActionResult.Failed("That app can't be opened")
                } else {
                    activity.startActivity(intent)
                    ActionResult.Success("Opened ${app.label}")
                }
            }
        }

    private fun runAction(action: PhoneAction): ActionResult = guarded(action) {
        when (action) {
            is PhoneAction.CallNumber -> callNumber(action.number)
            is PhoneAction.SetAlarm -> setAlarm(action)
            is PhoneAction.SetTimer -> setTimer(action.seconds)
            is PhoneAction.CloseApp -> goHome()
            is PhoneAction.SetRingerMode -> setRingerMode(action.mode)
            is PhoneAction.CallContact, is PhoneAction.OpenApp -> ActionResult.Failed("Try again")
        }
    }

    private fun guarded(action: PhoneAction, effect: () -> ActionResult): ActionResult {
        if (DeviceLock.requiresUnlock(activity, action)) return ActionResult.NeedsUnlock
        action.validationError()?.let { return ActionResult.Failed(it) }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return ActionResult.Failed("Open Friday to do that")
        }
        if (activity.isFinishing || activity.isDestroyed ||
            activity.window.decorView.windowVisibility != View.VISIBLE || !activity.window.decorView.isShown
        ) {
            return ActionResult.Failed("Open Friday to do that")
        }
        return try {
            effect()
        } catch (_: SecurityException) {
            permissionAfterFailure(action)
        } catch (_: ActivityNotFoundException) {
            ActionResult.Failed("No app can do that")
        } catch (_: RuntimeException) {
            // OEM action handlers and providers can fail even after permission checks.
            ActionResult.Failed("Try again")
        }
    }

    private fun callPermissionFailure(needsContacts: Boolean): ActionResult? = when {
        needsContacts && !hasPermission(Manifest.permission.READ_CONTACTS) ->
            ActionResult.NeedsPermission(RequiredAccess.CONTACTS)
        !hasPermission(Manifest.permission.CALL_PHONE) ->
            ActionResult.NeedsPermission(RequiredAccess.PHONE)
        else -> null
    }

    private fun callNumber(number: String): ActionResult {
        callPermissionFailure(needsContacts = false)?.let { return it }
        val normalized = normalizePhoneNumber(number)
            ?: return ActionResult.Failed("Invalid phone number")
        return placeCall(normalized, normalized)
    }

    private fun placeCall(number: String, label: String): ActionResult {
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
        if (!canHandle(intent)) return ActionResult.Failed("No calling app available")
        activity.startActivity(intent)
        // ACTION_CALL starts the call UI; connection to the other person is not promised.
        return ActionResult.Success("Calling $label")
    }

    private fun readContacts(): List<Contact>? {
        val contacts = linkedMapOf<Long, Contact>()
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.IS_SUPER_PRIMARY,
        )
        val cursor = activity.contentResolver.query(
            Phone.CONTENT_URI, projection, null, null, null,
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)?.trim().orEmpty()
                val number = normalizePhoneNumber(it.getString(2).orEmpty()) ?: continue
                if (name.isEmpty()) {
                    continue
                }
                val contact = contacts.getOrPut(id) { Contact(name, mutableListOf()) }
                contact.numbers.add(ContactNumber(number, it.getInt(3) == 1))
            }
        }
        return contacts.values.toList()
    }

    private fun setAlarm(action: PhoneAction.SetAlarm): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        action.label?.trim()?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        if (!canHandle(intent)) return ActionResult.Failed("No clock app available")
        activity.startActivity(intent)
        val hour = if (action.hour % 12 == 0) 12 else action.hour % 12
        val minute = String.format(Locale.US, "%02d", action.minute)
        val period = if (action.hour < 12) "AM" else "PM"
        // Clock intents provide no result confirming that the other app created the alarm.
        return ActionResult.Success("Alarm request sent for $hour:$minute $period")
    }

    private fun setTimer(seconds: Int): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!canHandle(intent)) return ActionResult.Failed("No clock app available")
        activity.startActivity(intent)
        val duration = if (seconds % 60 == 0) {
            val minutes = seconds / 60
            "$minutes ${if (minutes == 1) "minute" else "minutes"}"
        } else {
            "$seconds ${if (seconds == 1) "second" else "seconds"}"
        }
        return ActionResult.Success("Timer request sent for $duration")
    }

    private fun goHome(): ActionResult {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canHandle(intent)) return ActionResult.Failed("Home screen unavailable")
        activity.startActivity(intent)
        return ActionResult.Success("Went to home screen")
    }

    private fun setRingerMode(mode: RingerMode): ActionResult {
        val notificationManager = activity.getSystemService(NotificationManager::class.java)
        RingerModePolicy.requiredAccess(
            mode,
            notificationPolicyGranted = notificationManager?.isNotificationPolicyAccessGranted == true,
        )?.let { return ActionResult.NeedsPermission(it) }
        val audio = activity.getSystemService(AudioManager::class.java)
            ?: return ActionResult.Failed("Sound controls unavailable")
        if (audio.isVolumeFixed) return ActionResult.Failed("Sound mode can't be changed")
        val requested = when (mode) {
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        }
        audio.ringerMode = requested
        if (audio.ringerMode != requested) return ActionResult.Failed("Sound mode didn't change")
        return ActionResult.Success(
            when (mode) {
                RingerMode.SILENT -> "Silent mode on"
                RingerMode.VIBRATE -> "Vibrate mode on"
                RingerMode.NORMAL -> "Normal sound mode on"
            },
        )
    }

    private fun permissionAfterFailure(action: PhoneAction): ActionResult = when (action) {
        is PhoneAction.CallContact -> callPermissionFailure(needsContacts = true)
            ?: ActionResult.Failed("Calling unavailable")
        is PhoneAction.CallNumber -> callPermissionFailure(needsContacts = false)
            ?: ActionResult.Failed("Calling unavailable")
        is PhoneAction.SetRingerMode -> {
            val notificationManager = activity.getSystemService(NotificationManager::class.java)
            RingerModePolicy.requiredAccess(
                action.mode,
                notificationPolicyGranted = notificationManager?.isNotificationPolicyAccessGranted == true,
            )?.let { ActionResult.NeedsPermission(it) }
                ?: ActionResult.Failed("Sound mode can't be changed")
        }
        else -> ActionResult.Failed("Action unavailable")
    }

    private fun hasPermission(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canHandle(intent: Intent): Boolean = intent.resolveActivity(activity.packageManager) != null

    private data class Contact(val name: String, val numbers: MutableList<ContactNumber>)

    private data class ContactNumber(val number: String, val isDefault: Boolean)
}
