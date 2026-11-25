package com.puneet.friday.voice

import com.puneet.friday.actions.MediaOperation
import com.puneet.friday.actions.PhoneAction
import com.puneet.friday.actions.RingerMode
import com.puneet.friday.actions.SettingState
import com.puneet.friday.actions.SettingsTarget
import com.puneet.friday.actions.VolumeStream
import com.puneet.friday.actions.validationError
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

object VoiceProtocol {
    fun parseCommand(body: String, serverTimingHeader: String?): VoiceCommandResponse {
        val value = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BUSY)
        }
        if (value.optString("type") == "answer") {
            val answer = AnswerPayload.parse(toMap(value))
                ?: return VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BUSY)
            return VoiceCommandResponse.Answer(answer)
        }
        if (value.optString("type") == "actions") {
            val array = value.optJSONArray("actions")
                ?: return VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO)
            if (array.length() !in 2..3) return VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO)
            val steps = (0 until array.length()).map { index ->
                val step = array.optJSONObject(index)
                    ?: return VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO)
                VoiceResponseParser.parseFields(step.optString("type"), step.optString("action"),
                    step.optJSONObject("args")?.let(::toMap))
            }
            return VoiceResponseParser.parseSequence(steps, serverTimingHeader)
        }
        val args = value.optJSONObject("args")?.let(::toMap)
        return VoiceResponseParser.parseFields(
            type = value.optString("type"),
            actionName = value.optString("action"),
            args = args,
            reason = value.optString("reason"),
            serverTimingHeader = serverTimingHeader,
        )
    }

    fun parseServerDuration(header: String?): Double? {
        return VoiceResponseParser.parseServerDuration(header)
    }

    private fun toMap(value: JSONObject): Map<String, Any?> = buildMap {
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, jsonValue(value.opt(key)))
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
        else -> value
    }
}

object VoiceResponseParser {
    fun parseSequence(steps: List<VoiceCommandResponse>, timingHeader: String?): VoiceCommandResponse {
        if (steps.size !in 2..3) return badResponse()
        val actions = steps.map { (it as? VoiceCommandResponse.Action)?.action ?: return badResponse() }
        val sequence = PhoneAction.Sequence(actions)
        if (sequence.validationError() != null) return badResponse()
        return VoiceCommandResponse.Action(sequence, parseServerDuration(timingHeader))
    }

    fun parseServerDuration(header: String?): Double? {
        if (header == null) return null
        return SERVER_DURATION.find(header)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    fun parseFields(
        type: String,
        actionName: String = "",
        args: Map<String, Any?>? = null,
        reason: String = "",
        serverTimingHeader: String? = null,
    ): VoiceCommandResponse = when (type) {
        "action" -> parseAction(actionName, args, parseServerDuration(serverTimingHeader))
        "unsupported" -> VoiceCommandResponse.Unsupported
        "error" -> VoiceCommandResponse.Error(
            when (reason) {
                "timeout" -> VoiceCommandResponse.Reason.TIMEOUT
                "bad_audio" -> VoiceCommandResponse.Reason.BAD_AUDIO
                "unauthorized" -> VoiceCommandResponse.Reason.UNAUTHORIZED
                else -> VoiceCommandResponse.Reason.BUSY
            },
        )
        else -> badResponse()
    }

    private fun parseAction(
        actionName: String,
        args: Map<String, Any?>?,
        serverDurationMs: Double?,
    ): VoiceCommandResponse {
        if (args == null) return badResponse()
        val action = when (actionName) {
            "call_contact" -> if (hasOnlyKeys(args, "name")) {
                requiredString(args, "name", MAX_NAME_LENGTH)?.let(PhoneAction::CallContact)
            } else null
            "call_number" -> if (hasOnlyKeys(args, "number")) {
                requiredString(args, "number", MAX_NUMBER_LENGTH)?.let(PhoneAction::CallNumber)
            } else null
            "set_alarm" -> {
                val hour = requiredInt(args, "hour")
                val minute = requiredInt(args, "minute")
                if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59 ||
                    !hasOnlyKeys(args, "hour", "minute", "label")
                ) {
                    null
                } else {
                    val label = optionalString(args, "label", MAX_LABEL_LENGTH) ?: return badResponse()
                    PhoneAction.SetAlarm(hour, minute, label.value)
                }
            }
            "set_timer" -> if (hasOnlyKeys(args, "seconds")) {
                requiredInt(args, "seconds")?.takeIf { it > 0 }?.let(PhoneAction::SetTimer)
            } else null
            "open_app" -> if (hasOnlyKeys(args, "name")) {
                requiredString(args, "name", MAX_NAME_LENGTH)?.let(PhoneAction::OpenApp)
            } else null
            "open_music" -> if (args.isEmpty()) PhoneAction.OpenMusic else null
            "control_media" -> if (hasOnlyKeys(args, "operation")) {
                MediaOperation.entries.firstOrNull { it.wireName == args["operation"] }
                    ?.let(PhoneAction::ControlMedia)
            } else null
            "play_media" -> {
                if (!hasOnlyKeys(args, "query", "appName") ||
                    args.values.any { it !is String || it.any(Char::isISOControl) }) return badResponse()
                val query = requiredString(args, "query", 160) ?: return badResponse()
                val app = optionalString(args, "appName", MAX_NAME_LENGTH) ?: return badResponse()
                PhoneAction.PlayMedia(query, app.value)
            }
            "close_app" -> {
                if (!hasOnlyKeys(args, "name")) return badResponse()
                val name = optionalString(args, "name", MAX_NAME_LENGTH) ?: return badResponse()
                PhoneAction.CloseApp(name.value)
            }
            "set_ringer_mode" -> if (hasOnlyKeys(args, "mode")) {
                when (requiredString(args, "mode", 7)) {
                    "silent" -> PhoneAction.SetRingerMode(RingerMode.SILENT)
                    "vibrate" -> PhoneAction.SetRingerMode(RingerMode.VIBRATE)
                    "normal" -> PhoneAction.SetRingerMode(RingerMode.NORMAL)
                    else -> null
                }
            } else null
            "flashlight" -> if (hasOnlyKeys(args, "on")) {
                (args["on"] as? Boolean)?.let(PhoneAction::Flashlight)
            } else null
            "set_volume" -> if (hasOnlyKeys(args, "stream", "percent")) {
                val percent = requiredInt(args, "percent")
                val stream = when (args["stream"]) {
                    "media" -> VolumeStream.MEDIA
                    "ring" -> VolumeStream.RING
                    "alarm" -> VolumeStream.ALARM
                    else -> null
                }
                if (stream != null && percent != null && percent in 0..100) PhoneAction.SetVolume(stream, percent) else null
            } else null
            "open_settings" -> if (hasOnlyKeys(args, "setting", "state")) {
                val target = when (requiredString(args, "setting", 32)) {
                    "airplane_mode" -> SettingsTarget.AIRPLANE_MODE
                    else -> null
                }
                val state = when (val value = args["state"]) {
                    null -> null
                    "on" -> SettingState.ON
                    "off" -> SettingState.OFF
                    else -> return badResponse()
                }
                target?.let { PhoneAction.OpenSettings(it, state) }
            } else null
            else -> null
        } ?: return badResponse()
        return VoiceCommandResponse.Action(action, serverDurationMs)
    }

    private fun requiredString(value: Map<String, Any?>, key: String, maxLength: Int): String? {
        val text = value[key] as? String ?: return null
        val trimmed = text.trim()
        return trimmed.takeIf { it.isNotEmpty() && it.length <= maxLength }
    }

    private fun requiredInt(value: Map<String, Any?>, key: String): Int? {
        val number = value[key] as? Number ?: return null
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return long.toInt()
    }

    private data class OptionalString(val value: String?)

    private fun optionalString(value: Map<String, Any?>, key: String, maxLength: Int): OptionalString? {
        if (!value.containsKey(key)) return OptionalString(null)
        return requiredString(value, key, maxLength)?.let(::OptionalString)
    }

    private fun hasOnlyKeys(value: Map<String, Any?>, vararg allowed: String): Boolean =
        value.keys.all(allowed.toSet()::contains)

    private fun badResponse(): VoiceCommandResponse =
        VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO)

    private const val MAX_NAME_LENGTH = 100
    private const val MAX_NUMBER_LENGTH = 40
    private const val MAX_LABEL_LENGTH = 100
    private val SERVER_DURATION = Regex("(?:^|[,]\\s*)(?:gemini|groq)\\s*;\\s*dur=([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
}
