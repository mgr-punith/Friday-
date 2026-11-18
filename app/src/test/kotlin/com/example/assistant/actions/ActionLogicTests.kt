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
        expect(matched("Clock", listOf("Clock", "Calendar"), 0), "Match a launcher app label")
        expect(ambiguous("Clock", listOf("Clock", "Clock"), listOf(0, 1)), "Duplicate app labels need a choice")
        expect(NameMatcher.match("M".repeat(121), listOf("Mom")) == MatchResult.NotFound, "Reject unbounded matching input")
        expect(NameMatcher.match("Mom", listOf("Mom" + " ".repeat(121) + "Smith")) == MatchResult.NotFound, "Never match a truncated candidate")

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
        println("$checks action logic checks passed")
    }
}
