package dev.hermeskotlin.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermeskotlin.ui.settings.ensureALauncherIcon

/**
 * Wakes the app after a restart of the phone or an update of Herald, so Notifications anywhere picks its
 * ntfy stream up again ([dev.hermeskotlin.android.push.PushListener]); the work happens as the process
 * starts. It also puts Herald back in the launcher if an update removed the icon the user had picked
 * ([ensureALauncherIcon]).
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ensureALauncherIcon(context)
    }
}
