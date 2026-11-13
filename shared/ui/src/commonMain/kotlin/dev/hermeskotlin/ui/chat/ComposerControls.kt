package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hermeskotlin.core.settings.RunningSend

/** Per-draft selection: selecting a mode never submits the draft. */
internal class SendModeSelection {
    var picked by mutableStateOf<RunningSend?>(null)
        private set

    fun select(mode: RunningSend) { picked = mode }

    fun clear() { picked = null }

    fun mode(running: Boolean, setting: RunningSend, attachments: Boolean): RunningSend? =
        sendModeFor(running, picked, setting, attachments)

    fun explanation(running: Boolean, setting: RunningSend, attachments: Boolean): String? = when {
        !running -> null
        attachments && (picked ?: setting) == RunningSend.Steer ->
            "Files can't steer a reply. Send will queue this message until the reply ends."
        else -> mode(running, setting, attachments)?.summary
    }

    fun submit(
        running: Boolean,
        setting: RunningSend,
        attachments: Boolean,
        command: Boolean = false,
        send: (RunningSend?) -> Unit,
    ) {
        send(if (command) null else mode(running, setting, attachments))
        picked = null
    }
}

internal fun composerExpansion(focused: Boolean, hasDraft: Boolean, dictating: Boolean, editing: Boolean): Float =
    if (focused || hasDraft || dictating || editing) 1f else 0f
