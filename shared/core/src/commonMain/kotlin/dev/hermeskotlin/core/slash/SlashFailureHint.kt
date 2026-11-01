package dev.hermeskotlin.core.slash

private val SESSION_BOUND = setOf("heartbeat", "loop")
private val NO_SESSION = Regex("""unavailable \(no active session\)""", RegexOption.IGNORE_CASE)

/**
 * What to show instead of the gateway's own line when a session-bound command fails the way Hermes v0.22.5's slash
 * worker does (NousResearch/hermes-agent#147596), or null when [output] is something else.
 */
fun slashFailureHint(command: String, output: String): String? {
    if (command !in SESSION_BOUND || !NO_SESSION.containsMatchIn(output)) return null
    return "This gateway can't run /$command from the app: its Hermes build has a known bug " +
        "(NousResearch/hermes-agent#147596). A routine (Settings → Routines) does the same job and survives restarts."
}
