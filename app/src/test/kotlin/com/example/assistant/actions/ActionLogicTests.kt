package com.example.assistant.actions

/** Dependency-free JVM checks, run by the project's actionLogicTest Gradle task. */
object ActionLogicTests {
    @JvmStatic
    fun main(args: Array<String>) {
        var checks = 0
        fun expect(condition: Boolean, description: String) {
            check(condition) { description }
            checks++
        }
        fun matched(query: String, names: List<String>, index: Int) =
            NameMatcher.match(query, names) == MatchResult.Matched(index)
        fun ambiguous(query: String, names: List<String>, indices: List<Int>) =
            NameMatcher.match(query, names) == MatchResult.Ambiguous(indices)

        expect(NameMatcher.normalize("  JOSÉ   García  ") == "jose garcia", "Normalize accents, case, and spaces")
        expect(NameMatcher.normalize("O’Connor") == "oconnor", "Normalize apostrophes")
        expect(matched("jose garcia", listOf("Alex", "José García"), 1), "Match an accented contact locally")
        expect(matched("Al", listOf("Alex", "Al"), 1), "An exact short name is valid")
        expect(NameMatcher.match("Al", listOf("Alex")) == MatchResult.NotFound, "Never guess a short prefix")
        expect(NameMatcher.match("Jhon", listOf("John")) == MatchResult.NotFound, "Reject weak short-name typo")
        expect(ambiguous("Mom", listOf("Mom", "Móm"), listOf(0, 1)), "Duplicate normalized contacts need a choice")
        expect(matched("John", listOf("John Smith", "Mary"), 0), "A unique complete first name can match")
        expect(ambiguous("John", listOf("John Smith", "John Brown"), listOf(0, 1)), "Shared first names need a choice")
        expect(NameMatcher.match("John", listOf("Johnny")) == MatchResult.NotFound, "A substring is not a complete first name")
        expect(NameMatcher.match("Alex", listOf("Alexa")) == MatchResult.NotFound, "Do not extend a short name into a different person")
        expect(NameMatcher.match("Sara", listOf("Sarah")) == MatchResult.NotFound, "A short prefix is not a safe contact match")
        expect(matched("Smith", listOf("John Smith", "Mary Jones"), 0), "A unique complete last name can match")
        expect(matched("Aleander", listOf("Alexander", "Christine"), 0), "Accept one typo in a long name")
        expect(matched("Jonathon", listOf("Jonathan Smith", "Mary Jones"), 0), "Long first-name typo can match a token")
        expect(ambiguous("Alexand", listOf("Alexander", "Alexandr"), listOf(0, 1)), "Similar prefixes need a choice")
        expect(ambiguous("Samantha", listOf("Samanthe", "Samanthi"), listOf(0, 1)), "Equally close fuzzy matches need a choice")
        expect(matched("Punith", listOf("Puneet", "Arjun"), 0), "Match a common Indian long-vowel spelling variant")
        expect(matched("Pradip", listOf("Pradeep", "Prakash"), 0), "Match ee and i in a longer Indian name")
        expect(matched("Wikas", listOf("Vikas", "Vijay"), 0), "Match v and w pronunciation variants")
        expect(matched("Sures", listOf("Suresh", "Ramesh"), 0), "Match a terminal sh and s variant")
        expect(matched("Doctor Reddy", listOf("Dr Reddy", "Dr Mehta"), 0), "Match spoken doctor to a saved Dr title")
        expect(
            ambiguous("Punit", listOf("Puneet", "Punith"), listOf(0, 1)),
            "Indian phonetic collisions need a choice",
        )
        expect(NameMatcher.match("Wim", listOf("Vim")) == MatchResult.NotFound, "Do not phonetic-match short names")
        expect(NameMatcher.match("Sara", listOf("Sarah")) == MatchResult.NotFound, "Do not broaden unrelated final h names")
        expect(NameMatcher.match("Roberto", listOf("Alexander", "Maria")) == MatchResult.NotFound, "Reject unrelated contacts")
        expect(NameMatcher.match(" ", listOf("Mom")) == MatchResult.NotFound, "Empty queries never select a contact")
        expect(NameMatcher.match("Mom", emptyList()) == MatchResult.NotFound, "Empty device lists are handled")
        expect(NameMatcher.match("M".repeat(121), listOf("Mom")) == MatchResult.NotFound, "Reject unbounded matching input")
        expect(NameMatcher.match("Mom", listOf("Mom" + " ".repeat(121) + "Smith")) == MatchResult.NotFound, "Never match a truncated candidate")
        expect(
            NameMatcher.matchContact("Could you please ring Doctor Reddy, please?", listOf("Dr Reddy", "Dr Mehta")) ==
                MatchResult.Matched(0),
            "Separate the call verb from the doctor's name",
        )
        expect(
            NameMatcher.matchContact("Call Punith", listOf("Puneet", "Arjun")) == MatchResult.Matched(0),
            "Apply the existing spoken-name rules after a call verb",
        )
        expect(
            NameMatcher.matchContact("Please call John", listOf("John Smith", "John Brown")) ==
                MatchResult.Ambiguous(listOf(0, 1)),
            "Calls still require a choice for shared names",
        )
        expect(
            NameMatcher.matchContact("Call Al", listOf("Alex")) == MatchResult.NotFound,
            "Call verbs do not weaken the contact threshold",
        )
        expect(
            NameMatcher.matchContact("When I call Mom", listOf("Mom")) == MatchResult.NotFound,
            "Do not pull a contact out of the middle of a sentence",
        )

        val chrome = LauncherApp("Chrome", "com.android.chrome")
        val beta = LauncherApp("Chrome Beta", "com.chrome.beta")
        val whatsapp = LauncherApp("WhatsApp", "com.whatsapp")
        val phonePe = LauncherApp("PhonePe", "com.phonepe.app")
        val otherApps = listOf(beta, chrome, whatsapp, phonePe)
        expect(AppNameMatcher.match("Google Chrome", otherApps) == MatchResult.Matched(1), "Google Chrome opens stable Chrome")
        expect(
            AppNameMatcher.match("Could you open the Google Chrome app, please?", otherApps) == MatchResult.Matched(1),
            "Find Chrome when the provider includes the launch phrase",
        )
        expect(AppNameMatcher.match("GoogleChrome", otherApps) == MatchResult.Matched(1), "Joined vendor and app name")
        expect(AppNameMatcher.match("Google Chrome Beta", otherApps) == MatchResult.Matched(0), "Keep Chrome Beta distinct")
        expect(AppNameMatcher.match("Google Chrome", listOf(beta)) == MatchResult.NotFound, "No fallback to Chrome Beta")
        expect(AppNameMatcher.match("Google Chrome Beta", listOf(chrome)) == MatchResult.NotFound, "No fallback to stable Chrome")
        expect(AppNameMatcher.match("Google", otherApps) == MatchResult.NotFound, "Google alone does not open Chrome")
        expect(AppNameMatcher.match("Chrome settings", otherApps) == MatchResult.NotFound, "Do not discard a target qualifier")
        expect(AppNameMatcher.match("Launch Whats App", otherApps) == MatchResult.Matched(2), "Spoken spaces in WhatsApp")
        expect(AppNameMatcher.match("Phone Pe", otherApps) == MatchResult.Matched(3), "Spoken spaces in PhonePe")
        val maps = listOf(LauncherApp("Maps", "com.example.othermaps"), LauncherApp("Maps", "com.google.android.apps.maps"))
        expect(AppNameMatcher.match("Google Maps", maps) == MatchResult.Matched(1), "Brand disambiguates same-label apps")
        expect(AppNameMatcher.match("Maps", maps) == MatchResult.Ambiguous(listOf(0, 1)), "Duplicate app labels need a choice")
        expect(
            AppNameMatcher.match("Calculator", listOf(LauncherApp("Simple Calculator", "com.example.calculator"))) ==
                MatchResult.Matched(0),
            "Keep matching a whole word in an installed app label",
        )
        expect(
            AppNameMatcher.match("Open Camera", listOf(LauncherApp("Open Camera", "net.sourceforge.opencamera"))) ==
                MatchResult.Matched(0),
            "An installed app label can contain a launch verb",
        )
        expect(AppNameMatcher.match("Google Chrome", emptyList()) == MatchResult.NotFound, "Empty installed app list")
        expect(AppNameMatcher.match(" ", otherApps) == MatchResult.NotFound, "Empty app name")
        expect(AppNameMatcher.match("a".repeat(121), otherApps) == MatchResult.NotFound, "Bound app-name input")
        expect(
            AppNameMatcher.match("Chrome", listOf(chrome, LauncherApp("Chrome", "com.example.browser"))) ==
                MatchResult.Ambiguous(listOf(0, 1)),
            "A known alias must not hide duplicate exact app labels",
        )
        expect(
            AppNameMatcher.match("Whats App", listOf(whatsapp, LauncherApp("Whats App", "com.example.spaced"))) ==
                MatchResult.Matched(1),
            "Prefer an exact installed label over space-insensitive matching",
        )
        expect(AppNameMatcher.match("app", otherApps) == MatchResult.NotFound, "A generic app keyword is not a name")
        expect(
            AppNameMatcher.match("I installed Chrome yesterday", otherApps) == MatchResult.NotFound,
            "Do not pick an app from a keyword in conversation",
        )
        expect(
            AppNameMatcher.match("Clock", listOf(LauncherApp("Clock", "com.example.clock"))) == MatchResult.Matched(0),
            "Retain ordinary launcher-label matching",
        )

        expect(normalizePhoneNumber("+91 (98765) 43210") == "+919876543210", "Normalize formatted international numbers")
        expect(normalizePhoneNumber("555-0100") == "5550100", "Normalize local numbers")
        expect(normalizePhoneNumber("*123#") == null, "USSD must never become a phone call")
        expect(normalizePhoneNumber("5550100,12") == null, "Reject post-dial digits")
        expect(normalizePhoneNumber("5550100;12") == null, "Reject wait instructions")
        expect(normalizePhoneNumber("555CALL") == null, "Reject letters in numbers")
        expect(normalizePhoneNumber("12") == null, "Reject too-short numbers")
        expect(normalizePhoneNumber("1".repeat(16)) == null, "Reject numbers longer than E.164")
        expect(normalizePhoneNumber("91+9876543210") == null, "Reject embedded plus")
        expect(PhoneAction.CallNumber("*123#").validationError() != null, "Invalid call fails before a side effect")
        expect(PhoneAction.CallContact(" ").validationError() != null, "Empty contact name is invalid")
        expect(PhoneAction.OpenApp("\nClock").validationError() != null, "Control characters are invalid")
        expect(PhoneAction.SetAlarm(0, 0).validationError() == null, "Midnight is valid")
        expect(PhoneAction.SetAlarm(23, 59).validationError() == null, "Last minute of the day is valid")
        expect(PhoneAction.SetAlarm(24, 0).validationError() != null, "Reject invalid alarm hour")
        expect(PhoneAction.SetAlarm(6, -1).validationError() != null, "Reject invalid alarm minute")
        expect(PhoneAction.SetAlarm(6, 30, "x".repeat(81)).validationError() != null, "Bound alarm labels")
        expect(PhoneAction.SetTimer(1).validationError() == null, "Positive timer is valid")
        expect(PhoneAction.SetTimer(0).validationError() != null, "Zero timer is invalid")
        expect(PhoneAction.SetTimer(-10).validationError() != null, "Negative timer is invalid")
        expect(PhoneAction.CloseApp().validationError() == null, "Going home needs no name")
        expect(PhoneAction.SetRingerMode(RingerMode.SILENT).validationError() == null, "Typed ringer modes are valid")
        expect(
            RingerModePolicy.requiredAccess(RingerMode.SILENT, notificationPolicyGranted = false) ==
                RequiredAccess.NOTIFICATION_POLICY,
            "Silent mode requires Notification Policy access",
        )
        expect(
            RingerModePolicy.requiredAccess(RingerMode.SILENT, notificationPolicyGranted = true) == null,
            "Silent mode can run after Notification Policy access",
        )
        expect(
            RingerModePolicy.requiredAccess(RingerMode.VIBRATE, notificationPolicyGranted = false) == null,
            "Vibrate mode does not require Notification Policy access",
        )
        expect(
            RingerModePolicy.requiredAccess(RingerMode.NORMAL, notificationPolicyGranted = false) == null,
            "Normal mode does not require Notification Policy access",
        )

        val lockPolicyCases = listOf(
            PhoneAction.CallContact("Test contact") to false,
            PhoneAction.CallNumber("5550100") to false,
            PhoneAction.SetAlarm(6, 30) to true,
            PhoneAction.SetTimer(60) to false,
            PhoneAction.OpenApp("Clock") to true,
            PhoneAction.CloseApp() to true,
            PhoneAction.SetRingerMode(RingerMode.SILENT) to false,
            PhoneAction.SetRingerMode(RingerMode.VIBRATE) to false,
            PhoneAction.SetRingerMode(RingerMode.NORMAL) to false,
        )
        lockPolicyCases.forEach { (action, requiresUnlock) ->
            expect(
                LockedActionPolicy.requiresUnlock(action, locked = true) == requiresUnlock,
                "Locked action policy: $action",
            )
            expect(
                !LockedActionPolicy.requiresUnlock(action, locked = false),
                "Unlocked actions retain their existing permission and validation flow: $action",
            )
        }
        println("$checks action logic checks passed")
    }
}
