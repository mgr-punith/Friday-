package com.puneet.friday.actions

internal data class LauncherApp(val label: String, val packageName: String)

/** Match names returned by the command provider against launchable apps on this phone. */
internal object AppNameMatcher {
    private val launchVerb = Regex("^(?:(?:can|could|would) you )?(?:please )?(?:open|launch|start|go to) ")
    private val aliasPackages = mapOf(
        "com.android.chrome" to listOf("chrome", "google chrome", "chrome browser", "google chrome browser"),
        "com.chrome.beta" to listOf("chrome beta", "google chrome beta"),
        "com.chrome.dev" to listOf("chrome dev", "google chrome dev"),
        "com.chrome.canary" to listOf("chrome canary", "google chrome canary"),
        "com.google.android.apps.maps" to listOf("google maps"),
        "com.google.android.calendar" to listOf("google calendar"),
        "com.google.android.apps.docs" to listOf("google drive"),
        "com.google.android.apps.photos" to listOf("google photos"),
        "com.google.android.apps.messaging" to listOf("google messages"),
        "com.google.android.gm" to listOf("gmail", "google mail"),
        "com.android.vending" to listOf("google play", "google play store", "play store"),
        "com.spotify.music" to listOf("spotify"),
        "com.google.android.youtube" to listOf("youtube"),
        "com.google.android.apps.youtube.music" to listOf("youtube music", "yt music"),
    ).flatMap { (packageName, names) -> names.map { it.replace(" ", "") to packageName } }.toMap()

    fun match(query: String, apps: List<LauncherApp>): MatchResult {
        if (query.length > NameMatcher.MAX_NAME_LENGTH) return MatchResult.NotFound
        val name = NameMatcher.normalize(query)
        if (name.isEmpty()) return MatchResult.NotFound

        val unwrapped = launchVerb.replaceFirst(name, "").removeSuffix(" please")
        val bareName = unwrapped.removePrefix("the ").removePrefix("my ").removeSuffix(" app")
        val names = listOf(name, unwrapped, bareName).distinct().filter { it.isNotEmpty() }
        val labels = apps.map { it.label }
        val normalizedLabels = labels.map {
            if (it.length <= NameMatcher.MAX_NAME_LENGTH) NameMatcher.normalize(it) else ""
        }
        for (target in names) {
            val exact = normalizedLabels.indices.filter { normalizedLabels[it] == target }
            if (exact.isNotEmpty()) return NameMatcher.resultFor(exact)

            val joined = target.replace(" ", "")
            val packageName = aliasPackages[joined]
            if (packageName != null) {
                // A named product must not fall through to a different app when absent.
                return NameMatcher.resultFor(apps.indices.filter { apps[it].packageName == packageName })
            }

            if (joined.length >= 4) {
                val sameLetters = normalizedLabels.indices.filter { normalizedLabels[it].replace(" ", "") == joined }
                if (sameLetters.isNotEmpty()) return NameMatcher.resultFor(sameLetters)
            }
        }
        return names.firstNotNullOfOrNull { target ->
            NameMatcher.match(target, labels).takeUnless { it == MatchResult.NotFound }
        } ?: MatchResult.NotFound
    }
}

internal object MusicApps {
    const val SPOTIFY = "com.spotify.music"
    const val PREFS = "music_player"
    const val PREFERRED = "preferred"
    data class Selection(val apps: List<LauncherApp>, val question: String? = null)

    enum class NamedRequestRoute {
        PLAY_FROM_SEARCH,
        SEARCH_RESULTS,
    }

    fun namedRequestRoute(packageName: String): NamedRequestRoute =
        if (packageName == "com.google.android.youtube") NamedRequestRoute.SEARCH_RESULTS
        else NamedRequestRoute.PLAY_FROM_SEARCH

    fun select(name: String?, available: List<LauncherApp>, preferred: String?): Selection {
        val candidates = available
        if (name != null) {
            val match = if (name.equals("Spotify", ignoreCase = true)) {
                NameMatcher.resultFor(available.indices.filter { available[it].packageName == SPOTIFY })
            } else AppNameMatcher.match(name, available)
            return when (match) {
                is MatchResult.Matched -> Selection(listOf(available[match.index]))
                is MatchResult.Ambiguous -> Selection(match.indices.map { available[it] }, "Which music app?")
                MatchResult.NotFound -> Selection(candidates, "Couldn't find $name. Which player should I use?")
            }
        }
        candidates.find { it.packageName == preferred }?.let { return Selection(listOf(it)) }
        return Selection(candidates, "Choose your default music app")
    }

    private val supportedServices = setOf(
        "com.spotify.music", "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "org.videolan.vlc", "com.android.bbkmusic",
    )
    private val playerWords = setOf("music", "audio", "player")

    fun find(launchers: List<LauncherApp>, musicPackages: Set<String>, audioHandlers: Set<String>): List<LauncherApp> =
        launchers.asSequence().filter { app ->
            app.packageName in supportedServices || app.packageName in musicPackages ||
                (app.packageName in audioHandlers && NameMatcher.normalize(app.label).split(' ').any { it in playerWords })
        }.distinctBy { it.packageName }.sortedBy { NameMatcher.normalize(it.label) }.toList()
}
