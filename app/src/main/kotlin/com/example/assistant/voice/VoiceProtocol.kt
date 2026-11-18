package com.example.assistant.voice

import com.example.assistant.actions.PhoneAction
import com.example.assistant.actions.RingerMode
import org.json.JSONObject

object VoiceProtocol {
    fun parseCommand(body: String, serverTimingHeader: String?): VoiceCommandResponse {
        val value = try {
            JSONObject(body)
        } catch (_: RuntimeException) {
            return VoiceCommandResponse.Error(VoiceCommandResponse.Reason.BAD_AUDIO)
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
            put(key, value.opt(key).takeUnless { it === JSONObject.NULL })
        }
    }
}

object VoiceResponseParser {
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
    private val SERVER_DURATION = Regex("(?:^|[,]\\s*)gemini\\s*;\\s*dur=([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
}
