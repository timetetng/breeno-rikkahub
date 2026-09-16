package com.timetetng.breeno.bridge

/**
 * Static configuration.
 *
 * There is deliberately no settings UI yet: a module that injects into another app's
 * process cannot read its own SharedPreferences under modern Android, and the usual
 * workarounds (world-readable prefs / a second IPC channel) are a lot of machinery for
 * three values. Change them here and let CI rebuild.
 */
object Config {

    /** Host process we hook. OPPO / OnePlus / realme Breeno assistant. */
    const val TARGET_PACKAGE = "com.heytap.speechassist"

    /** RikkaHub's embedded Ktor server. Keep it on loopback. */
    const val RIKKAHUB_HOST = "127.0.0.1"
    const val RIKKAHUB_PORT = 8080

    /**
     * Which RikkaHub conversation is the "Breno" one. Matched by exact title first,
     * then case-insensitive contains; when nothing matches we fall back to the most
     * recently updated conversation so the module still works out of the box.
     */
    const val CONVERSATION_TITLE = "小布"

    /** Master switch — set false to make the module a no-op without uninstalling it. */
    const val ENABLED = true

    /**
     * When true the module hooks the data layer but only logs; it does not touch the UI.
     * Handy for verifying that the target classes still resolve after a Breeno update.
     */
    const val DRY_RUN = false

    /**
     * Log every call into AIChatDataCenter plus every AIChatViewBean construction.
     *
     * Obfuscated method letters move between Breeno versions, so when the bridge stops
     * firing this is how you find the new ones: turn it on, trigger one utterance, then
     * read `logcat -s BrenoRikka` and look for the call carrying the user's text.
     */
    const val DEBUG_TRACE = true

    val baseUrl: String get() = "http://$RIKKAHUB_HOST:$RIKKAHUB_PORT"
}
