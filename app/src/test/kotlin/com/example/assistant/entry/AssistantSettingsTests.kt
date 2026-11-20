package com.example.assistant.entry

/** Simulate missing or blocked Android settings screens without an Android test dependency. */
object AssistantSettingsTests {
    fun run(): Int {
        var checks = 0
        fun scenario(
            description: String,
            from: AssistantSettings.Destination = AssistantSettings.Destination.ASSISTANT,
            unavailable: Set<AssistantSettings.Destination> = emptySet(),
            blocked: Set<AssistantSettings.Destination> = emptySet(),
            expected: AssistantSettings.Destination?,
            attempts: List<AssistantSettings.Destination>,
        ) {
            val visited = mutableListOf<AssistantSettings.Destination>()
            val result = AssistantSettings.firstAvailable(from) { destination ->
                visited += destination
                if (destination in blocked) throw SecurityException("Blocked by device policy")
                if (destination in unavailable) throw UnsupportedOperationException("No settings handler")
            }
            check(result == expected) { "$description: expected $expected, got $result" }
            check(visited == attempts) { "$description: unexpected launch order $visited" }
            checks += 2
        }

        val assistant = AssistantSettings.Destination.ASSISTANT
        val defaults = AssistantSettings.Destination.DEFAULT_APPS
        val settings = AssistantSettings.Destination.SETTINGS
        scenario("Open assistant selection without extra screens", expected = assistant, attempts = listOf(assistant))
        scenario("Missing assistant screen falls back to Default apps", unavailable = setOf(assistant),
            expected = defaults, attempts = listOf(assistant, defaults))
        scenario("Blocked assistant screen falls back to Default apps", blocked = setOf(assistant),
            expected = defaults, attempts = listOf(assistant, defaults))
        scenario("Missing screens fall back to Settings", unavailable = setOf(assistant, defaults),
            expected = settings, attempts = listOf(assistant, defaults, settings))
        scenario("No settings handlers returns a failure", unavailable = setOf(assistant, defaults, settings),
            expected = null, attempts = listOf(assistant, defaults, settings))
        scenario("Device policy can block all settings without crashing", blocked = setOf(assistant, defaults, settings),
            expected = null, attempts = listOf(assistant, defaults, settings))
        scenario("Manual Default apps fallback skips the earlier screen", from = defaults,
            expected = defaults, attempts = listOf(defaults))
        scenario("Manual fallback still handles blocked Default apps", from = defaults, blocked = setOf(defaults),
            expected = settings, attempts = listOf(defaults, settings))
        return checks
    }
}
