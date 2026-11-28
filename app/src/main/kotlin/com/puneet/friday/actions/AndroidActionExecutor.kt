package com.puneet.friday.actions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import android.view.View
import java.util.Locale

/** Resolves names locally on a worker; runs Android effects only from the visible UI. */
class AndroidActionExecutor(private val activity: Activity) {
    sealed interface Preparation {
        /** Invoke on the UI thread after any user choice or confirmation. */
        data class Ready(
            val action: PhoneAction,
            val confirmationTarget: String? = null,
            val runDirectIfUnlocked: (() -> ActionResult?)? = null,
            val run: () -> ActionResult,
        ) : Preparation
        data class Choice(val action: PhoneAction, val question: String, val options: List<ChoiceOption>) : Preparation
        data class Finished(val result: ActionResult) : Preparation
    }

    class ChoiceOption(val label: String, val spokenLabel: String = label, private val resolve: () -> Preparation) {
        constructor(label: String, ready: Preparation.Ready, spokenLabel: String = label) : this(label, spokenLabel, { ready })

        /** Run on the action worker after the user picks this option. */
        fun prepare(): Preparation = try {
            resolve()
        } catch (_: RuntimeException) {
            Preparation.Finished(ActionResult.Failed("That choice is unavailable"))
        }
    }

    /** Worker-safe. Reads local providers and builds intents; never starts an action. */
    fun prepare(action: PhoneAction): Preparation {
        if (DeviceLock.requiresUnlock(activity, action)) return Preparation.Finished(ActionResult.NeedsUnlock)
        action.validationError()?.let { return Preparation.Finished(ActionResult.Failed(it)) }
        return try {
            when (action) {
                is PhoneAction.CallContact -> prepareContact(action)
                is PhoneAction.OpenApp -> prepareApp(action)
                is PhoneAction.OpenMusic -> prepareMusic(action)
                is PhoneAction.PlayMedia -> prepareNamedMusic(action)
                is PhoneAction.OpenSettings -> Preparation.Ready(action) { runAction(action) }
                is PhoneAction.CallNumber -> Preparation.Ready(action, confirmationTarget = action.number) { runAction(action) }
                is PhoneAction.ControlMedia -> {
                    val previousController = if (action.operation == MediaOperation.PREVIOUS) {
                        PreviousTrackMediaSessionResolver(activity).findController()
                    } else {
                        null
                    }
                    Preparation.Ready(action) { guarded(action) { controlMedia(action.operation, previousController) } }
                }
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
                    val spoken = if (needsSuffix) "${contact.name}, ending in ${number.number.takeLast(4).toList().joinToString(" ")}"
                        else contact.name
                    ChoiceOption(label, readyCall(action, number.number, contact.name), spoken)
                }
                Preparation.Choice(action, "Which one should I call?", options)
            }
            MatchResult.NotFound -> Preparation.Finished(ActionResult.Failed("I couldn't find that contact. Say the name as it's saved."))
        }
    }

    private fun prepareContactNumbers(action: PhoneAction.CallContact, contact: Contact): Preparation {
        val numbers = uniqueNumbers(contact)
        preferredNumber(numbers)?.let {
            val direct = numbers.size == 1 && NameMatcher.isExactContactName(action.name, contact.name)
            return readyCall(action, it.number, contact.name, direct)
        }
        return Preparation.Choice(
            action,
            "Which number for ${contact.name}?",
            numbers.map { number ->
                val suffix = number.number.takeLast(4)
                val label = if (numbers.count { it.number.takeLast(4) == suffix } == 1) {
                    "Ending $suffix"
                } else number.number
                ChoiceOption(label, readyCall(action, number.number, contact.name),
                    "Ending in ${suffix.toList().joinToString(" ")}")
            },
        )
    }

    private fun uniqueNumbers(contact: Contact): List<ContactNumber> =
        contact.numbers.groupBy { it.number }.map { (number, entries) ->
            ContactNumber(number, entries.any { it.isDefault })
        }

    private fun preferredNumber(numbers: List<ContactNumber>): ContactNumber? =
        if (numbers.size == 1) numbers.single() else numbers.filter { it.isDefault }.singleOrNull()

    private fun readyCall(action: PhoneAction.CallContact, number: String, name: String, direct: Boolean = false): Preparation.Ready {
        val place = {
            guarded(action) {
                callPermissionFailure(needsContacts = true) ?: placeCall(number, name)
            }
        }
        val directCall: (() -> ActionResult?)? =
            if (direct) ({ if (DeviceLock.isLocked(activity)) null else place() }) else null
        return Preparation.Ready(action, confirmationTarget = "$name · $number", runDirectIfUnlocked = directCall, run = place)
    }

    private fun prepareApp(action: PhoneAction.OpenApp): Preparation {
        val apps = launcherActivities().map(::launcherApp)
        return when (val match = AppNameMatcher.match(action.name, apps)) {
            is MatchResult.Matched -> readyApp(action, apps[match.index])
            is MatchResult.Ambiguous -> chooseApp(action, match.indices.map { apps[it] }, "Which app?")
            MatchResult.NotFound -> Preparation.Finished(ActionResult.Failed("I couldn't find that app"))
        }
    }

    private val musicPrefs = activity.getSharedPreferences(MusicApps.PREFS, Activity.MODE_PRIVATE)

    internal fun setPreferredPlayer(app: LauncherApp?) {
        musicPrefs.edit().putString(MusicApps.PREFERRED, app?.packageName).apply()
    }

    private fun prepareMusic(action: PhoneAction.OpenMusic): Preparation =
        selectPlayer(action, action.appName) { app ->
            { if (action.play) requestPlayerPlayback(app) else readyApp(action, app).run() }
        }

    private fun requestPlayerPlayback(app: LauncherApp): ActionResult {
        val manager = activity.packageManager
        val button = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.packageName)
        val receiver = manager.queryBroadcastReceivers(button, 0).map { it.activityInfo }.firstOrNull {
            it.exported && (it.permission == null || hasPermission(it.permission))
        }
        if (receiver != null) {
            button.setClassName(app.packageName, receiver.name)
            val now = SystemClock.uptimeMillis()
            for (direction in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val key = KeyEvent(now, SystemClock.uptimeMillis(), direction, KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                activity.sendBroadcast(Intent(button).putExtra(Intent.EXTRA_KEY_EVENT, key))
            }
            return ActionResult.MediaRequest("Playback requested in ${app.label}")
        }
        if (DeviceLock.isLocked(activity)) return ActionResult.Failed("${app.label} can't resume music while locked.")
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.packageName)
            .putExtra(android.app.SearchManager.QUERY, "")
        if (!canHandle(play)) return ActionResult.Failed("${app.label} doesn't support voice playback")
        return startMusicActivity(play, "Playback requested in ${app.label}")
    }

    private fun selectPlayer(action: PhoneAction, name: String?,
        preparePlayback: (LauncherApp) -> () -> ActionResult): Preparation {
        val choice = MusicApps.select(name, musicApps(), musicPrefs.getString(MusicApps.PREFERRED, null))
        if (choice.apps.isEmpty()) return Preparation.Finished(ActionResult.Failed("I couldn't find a music app"))
        val needsChoice = choice.question != null
        val unavailableChoice = ActionResult.Failed(if (name == null) "Choose a default music app in Friday Settings."
            else "Choose a music app while your phone is unlocked.")
        if (needsChoice && DeviceLock.isLocked(activity)) return Preparation.Finished(unavailableChoice)
        fun ready(app: LauncherApp): Preparation {
            if (needsChoice && DeviceLock.isLocked(activity)) return Preparation.Finished(unavailableChoice)
            val run = preparePlayback(app)
            return Preparation.Ready(action) {
                guarded(action) {
                    if (needsChoice && DeviceLock.isLocked(activity)) return@guarded unavailableChoice
                    if (name == null) {
                        if (needsChoice) setPreferredPlayer(app)
                        else if (musicPrefs.getString(MusicApps.PREFERRED, null) != app.packageName) {
                            return@guarded ActionResult.Failed("Your default music app changed. Ask again.")
                        }
                    }
                    run()
                }
            }
        }
        return choice.question?.let { chooseApp(action, choice.apps, it, ::ready) } ?: ready(choice.apps.single())
    }

    internal fun musicApps(): List<LauncherApp> {
        val manager = activity.packageManager
        val launchers = launcherActivities()
        val musicPackages = launchers.filter { it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_AUDIO }
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
        manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .mapTo(musicPackages) { it.activityInfo.packageName }
        val playbackApps = manager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH),
            PackageManager.MATCH_DEFAULT_ONLY).filter {
                it.activityInfo.exported && (it.activityInfo.permission == null || hasPermission(it.activityInfo.permission))
            }.map { LauncherApp(it.activityInfo.applicationInfo.loadLabel(manager).toString(), it.activityInfo.packageName) }
        val browsers = manager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.filter {
                it.exported && (it.permission == null || hasPermission(it.permission))
            }.map { LauncherApp(it.applicationInfo.loadLabel(manager).toString(), it.packageName) }
        val mediaApps = playbackApps + browsers
        mediaApps.mapTo(musicPackages) { it.packageName }
        // The query declares an audio type. Friday neither opens media here nor scans the user's files.
        val audio = Intent(Intent.ACTION_VIEW).setDataAndType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/mpeg")
        val audioHandlers = manager.queryIntentActivities(audio, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
        return MusicApps.find(launchers.map(::launcherApp) + mediaApps, musicPackages, audioHandlers)
    }

    private fun prepareNamedMusic(action: PhoneAction.PlayMedia): Preparation =
        selectPlayer(action, action.appName) { app ->
            val session = if (PhoneCapabilityRegistry.requiresUnlock(action)) null else try {
                MusicPlaybackSession(activity.applicationContext).find(app.packageName)
            } catch (_: RuntimeException) { null }
            val playback = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(app.packageName)
                .putExtra(android.app.SearchManager.QUERY, action.query)
            if (action.mediaType == MusicItemType.TRACK) {
                playback.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                playback.putExtra(MediaStore.EXTRA_MEDIA_TITLE, action.query)
            } else playback.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            val run = {
                try {
                    when {
                        session?.playTitle(action.query, playback.extras ?: Bundle.EMPTY) == true ->
                            ActionResult.MediaRequest("Playback requested in ${app.label}")
                        !DeviceLock.isLocked(activity) -> openNamedMusic(action, app, playback)
                        app.packageName == MusicApps.SPOTIFY ->
                            ActionResult.Failed("Spotify can resume music here, but can't start a new title while locked.")
                        else -> ActionResult.Failed("${app.label} can't start a new title while locked.")
                    }
                } finally {
                    session?.close()
                }
            }
            run
        }

    private fun openNamedMusic(action: PhoneAction.PlayMedia, app: LauncherApp, playback: Intent): ActionResult {
        val mayPlay = !PhoneCapabilityRegistry.requiresUnlock(action)
        val youtube = MusicApps.namedRequestRoute(app.packageName) == MusicApps.NamedRequestRoute.SEARCH_RESULTS
        val spotify = app.packageName == MusicApps.SPOTIFY
        if (mayPlay && !youtube && !spotify && canHandle(playback)) {
            return startMusicActivity(playback, "Playback requested in ${app.label}")
        }
        val search = when {
            spotify -> Intent(Intent.ACTION_VIEW,
                Uri.parse("spotify:search:" + Uri.encode(action.query)))
            youtube -> Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("https").authority("www.youtube.com")
                .appendPath("results").appendQueryParameter("search_query", action.query).build())
            else -> Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, action.query)
        }.setPackage(app.packageName)
        if (canHandle(search)) return startMusicActivity(search, "Opened search in ${app.label}")
        if (mayPlay && (youtube || spotify) && canHandle(playback)) {
            return startMusicActivity(playback, "Playback requested in ${app.label}")
        }
        val launch = activity.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ActionResult.Failed("That player is unavailable")
        return startMusicActivity(launch, "Opened ${app.label}. Search for ${action.query} there.")
    }

    private fun startMusicActivity(intent: Intent, message: String): ActionResult {
        if (DeviceLock.isLocked(activity)) return ActionResult.Failed("Your phone locked before the music app opened.")
        activity.startActivity(intent)
        return ActionResult.Handoff(message, music = true)
    }

    private fun launcherActivities() = activity.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .distinctBy { it.activityInfo.packageName }

    private fun launcherApp(info: android.content.pm.ResolveInfo): LauncherApp =
        LauncherApp(info.loadLabel(activity.packageManager).toString(), info.activityInfo.packageName)

    private fun chooseApp(action: PhoneAction, apps: List<LauncherApp>, question: String,
        prepare: (LauncherApp) -> Preparation = { readyApp(action, it) }): Preparation.Choice {
        val duplicateNames = apps.groupingBy { NameMatcher.normalize(it.label) }.eachCount()
        return Preparation.Choice(action, question, apps.map { app ->
            val label = if (duplicateNames[NameMatcher.normalize(app.label)] != 1) {
                "${app.label} · ${app.packageName}"
            } else app.label
            ChoiceOption(label) { prepare(app) }
        })
    }

    private fun readyApp(action: PhoneAction, app: LauncherApp): Preparation.Ready =
        Preparation.Ready(action) {
            guarded(action) {
                val intent = activity.packageManager.getLaunchIntentForPackage(app.packageName)
                if (intent == null) {
                    ActionResult.Failed("That app can't be opened")
                } else {
                    activity.startActivity(intent)
                    ActionResult.Success("Opened ${app.label}", openedActivity = true)
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
            is PhoneAction.Flashlight -> setFlashlight(action.on)
            is PhoneAction.SetVolume -> changeVolume(action.stream, action.percent)
            is PhoneAction.AdjustVolume -> changeVolume(action.stream, action.percent, action.direction)
            is PhoneAction.ControlMedia -> controlMedia(action.operation, null)
            is PhoneAction.OpenSettings -> openSettings(action)
            is PhoneAction.CallContact, is PhoneAction.OpenApp, is PhoneAction.Sequence, is PhoneAction.OpenMusic, is PhoneAction.PlayMedia ->
                ActionResult.Failed("Try again")
        }
    }

    private fun controlMedia(operation: MediaOperation, previousController: MediaController?): ActionResult {
        if (operation == MediaOperation.PREVIOUS && previousController != null) {
            val state = previousController.playbackState
            if (state?.state in setOf(android.media.session.PlaybackState.STATE_PLAYING, android.media.session.PlaybackState.STATE_PAUSED)) {
                val controls = previousController.transportControls
                PreviousTrackCommand.send(
                    seekToStart = { controls.seekTo(0L) },
                    skipToPrevious = { controls.skipToPrevious() },
                )
                return ActionResult.MediaRequest(operation.requested)
            }
        }
        val audio = activity.getSystemService(AudioManager::class.java)
            ?: return ActionResult.Failed("Media controls unavailable")
        val key = when (operation) {
            MediaOperation.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaOperation.RESUME -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaOperation.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaOperation.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
        return ActionResult.MediaRequest(operation.requested)
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
        return ActionResult.Success("Calling $label", openedActivity = true)
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
        return ActionResult.Success("Alarm request sent for $hour:$minute $period", openedActivity = true)
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
        return ActionResult.Success("Timer request sent for $duration", openedActivity = true)
    }

    private fun goHome(): ActionResult {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canHandle(intent)) return ActionResult.Failed("Home screen unavailable")
        activity.startActivity(intent)
        return ActionResult.Success("Went to home screen", openedActivity = true)
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

    private fun setFlashlight(on: Boolean): ActionResult {
        val camera = activity.getSystemService(CameraManager::class.java)
            ?: return ActionResult.Failed("Flashlight unavailable")
        return try {
            val flash = camera.cameraIdList.mapNotNull { id ->
                val properties = camera.getCameraCharacteristics(id)
                if (properties.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) null
                else id to (properties.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
            }
            val id = (flash.firstOrNull { it.second } ?: flash.firstOrNull())?.first
                ?: return ActionResult.Failed("This phone has no flashlight")
            camera.setTorchMode(id, on)
            ActionResult.Success(if (on) "Flashlight on" else "Flashlight off")
        } catch (error: CameraAccessException) {
            val busy = error.reason == CameraAccessException.CAMERA_IN_USE ||
                error.reason == CameraAccessException.MAX_CAMERAS_IN_USE
            ActionResult.Failed(if (busy) "Close the camera and try again" else "Flashlight unavailable")
        }
    }

    private fun changeVolume(volume: VolumeStream, percent: Int, direction: VolumeDirection? = null): ActionResult {
        val audio = activity.getSystemService(AudioManager::class.java)
            ?: return ActionResult.Failed("Volume controls unavailable")
        if (audio.isVolumeFixed) return ActionResult.Failed("Volume can't be changed")
        val stream = when (volume) {
            VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        }
        val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        val maximum = audio.getStreamMaxVolume(stream)
        if (maximum <= minimum) return ActionResult.Failed("Volume can't be changed")
        val range = VolumeRange(minimum, maximum)
        if (direction == null) {
            val requested = range.levelFor(percent)
            audio.setStreamVolume(stream, requested, 0)
            if (audio.getStreamVolume(stream) != requested) return ActionResult.Failed("Volume didn't change")
            return ActionResult.Success("${volume.label} volume set to ${range.percentAt(requested)}%")
        }

        val current = audio.getStreamVolume(stream)
        val atLimit = if (direction == VolumeDirection.UP) current >= maximum else current <= minimum
        if (atLimit) {
            val endpoint = if (direction == VolumeDirection.UP) "maximum" else "minimum"
            return ActionResult.Success("${volume.label} volume is already at $endpoint")
        }
        val requested = range.adjustedLevel(current, direction, percent)
        if (requested == current.coerceIn(minimum, maximum)) {
            return ActionResult.Failed("$percent% is too small to change ${volume.wireName} volume")
        }
        audio.setStreamVolume(stream, requested, 0)
        val applied = audio.getStreamVolume(stream)
        if (applied == current) return ActionResult.Failed("${volume.label} volume stayed at ${range.percentAt(current)}%")
        return ActionResult.Success("${volume.label} volume is ${range.percentAt(applied)}%")
    }

    private fun openSettings(action: PhoneAction.OpenSettings): ActionResult {
        SettingsHandoff.open(action.target, activity::startActivity)
            ?: return ActionResult.Failed("Android Settings unavailable")
        return ActionResult.Handoff(SettingsHandoff.instruction(action))
    }

    private fun permissionAfterFailure(action: PhoneAction): ActionResult = when (action) {
        is PhoneAction.Flashlight -> ActionResult.Failed("Flashlight unavailable")
        is PhoneAction.SetVolume -> volumePermissionFailure(action.stream)
        is PhoneAction.AdjustVolume -> volumePermissionFailure(action.stream)
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

    private fun volumePermissionFailure(stream: VolumeStream): ActionResult {
        val policy = activity.getSystemService(NotificationManager::class.java)
        return if (stream == VolumeStream.RING && policy?.isNotificationPolicyAccessGranted != true) {
            ActionResult.NeedsPermission(RequiredAccess.NOTIFICATION_POLICY)
        } else ActionResult.Failed("Volume can't be changed")
    }

    private fun hasPermission(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canHandle(intent: Intent): Boolean = intent.resolveActivity(activity.packageManager) != null

    private data class Contact(val name: String, val numbers: MutableList<ContactNumber>)

    private data class ContactNumber(val number: String, val isDefault: Boolean)
}
