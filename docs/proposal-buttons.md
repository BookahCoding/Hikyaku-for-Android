# Approve and Deny on proposal notifications

When the proposals plugin asks about a proposal, the phone notification gets two buttons:
**Approve** and **Deny**. They run `/proposal yes N` or `/proposal no N` in the bot's chat as a **slash command**, the
way the composer does (`BotsApi.runBotCommand`: `session.resume`, then `slash.exec`, then `command.dispatch` for what
the slash worker won't take). Never as a prompt: an answer that would turn into a prompt (a skill, an alias) is refused. Spec: Desk Buddy home-lab screens spec, section 6.

## How it decides N

The app reads the number **only** from a machine marker in the message: `[proposal:12]` (case and spaces inside the
brackets do not matter). Exactly one distinct marker is required. No marker, two different numbers, `0`, or a number
of ten digits or more: no buttons, the notification stays an ordinary one. Text such as `Proposal #12` or
`/proposal yes 12` is deliberately not read: a button that answers the wrong proposal is worse than none.

**The proposals plugin must add `[proposal:N]` to the message that asks for an answer** (only to that message, not to
"approved" or "expired" notices, or those would get buttons too).

## Where it applies

* A bot message (`postBotMessage`): the notification shown when a bot wrote in its chat, from the gateway socket or
  from push. Buttons go into the bot's chat the message came from.
* A followed cron run (`postCronRun`): `proposals-tick` is a script job, so with **Notify** on it reaches the phone as
  a cron run, which has no chat of its own. The app then looks up the bot's Bot Chat when a button is tapped.
* Each proposal has its own notification (`<bot>/proposal/<N>`): two waiting proposals do not hide each other, and the
  same question arriving by push and by chat replaces itself. A proposal this app already answered never gets its
  buttons back (until the app process restarts).
* A bot the phone may not hear from (the profile list) never posts anything, as before.

## What a tap does

* **Approve** needs the phone unlocked: the button is marked "authentication required" (Android 12 and later asks for
  the unlock), and the receiver checks the lock state again, so on an older Android, or if it got through, nothing is
  sent and "Unlock your phone to approve proposal #N" appears instead. The question keeps its buttons.
* **Deny** needs no unlock (the spec only requires it for Approve).
* The notification turns into "Sending approval…", then shows **what the plugin printed** (for example a refusal such
  as "no such proposal"), or "Approval sent." when it printed nothing (gone after 20 s). A failure stays on screen with
  "Couldn't send. Open Hikyaku to answer." and the buttons are gone (open the app to answer).
* A second tap, or the other button, while the first is on its way or after it went, does nothing.

## Known limits

* Any text with a marker gets buttons, including a model reply that quotes or invents `[proposal:7]`. The title shows
  the number ("Proposal #7") so it can be checked, and the plugin refuses a number that is not pending.
* Not checked on a real gateway: that `slash.exec` accepts `/proposal` from this client (the composer's own commands
  and plugin commands go this way, and there is a `command.dispatch` fallback). Step 4 of the test tells.
* The dev build was installed nowhere; nothing here has run on a phone.
* The notification code (`ChatNotifications`, `NotificationActionReceiver`) has no automated test: the app has no
  Android test setup. Tested by unit tests: the marker reader, the exact commands and the answered-once guard
  (`ProposalMarkerTest`). Everything else is compile-and-review until it is tried on a phone.

## Install the dev build next to Hikyaku

Build: `gradlew :androidApp:assembleDev` (Git Bash needs `JAVA_HOME` set to Android Studio's `jbr`). Output:
`androidApp/build/outputs/apk/dev/androidApp-dev.apk`, package `dev.herald.android.dev`, signed with the debug key.
It is a **separate app** beside Hikyaku (`dev.herald.android`, release key): it cannot replace or update Hikyaku, and
Hikyaku must not be uninstalled for it. It has its own sign-in and settings, so sign in to the gateway in it once.

* USB: with USB debugging on and the phone listed by `adb devices`:
  `adb install -r androidApp/build/outputs/apk/dev/androidApp-dev.apk`
* Or copy the APK to the phone any other way and open it, allowing "install unknown apps" for that app once.

## Test it (no real proposal, nothing on the gateway touched)

0. In the dev app leave **Notifications anywhere** and **Phone tools** OFF. Turning them on registers a second phone
   on the gateway, which changes it. Uninstall the dev app when the test is over. With
   them off the cron-run path (`postCronRun`, which looks up the Bot Chat on tap) cannot be tried by this build.
1. In the dev app, sign in, allow notifications, open **Shisha's Bot Chat** (the bot's own permanent chat, not a
   Telegram or other chat: only the Bot Chat raises the notification this build changes).
2. Type: "Reply with exactly: Test [proposal:999999]" and send it, then press Home at once so the app is in the
   background but still connected (its quiet "Connected to Shisha" notification stays up). Hikyaku will show the same
   reply as a plain notification without buttons; look at the dev app's one.
3. The dev app's notification should be titled **Proposal #999999** with **Approve** and **Deny**.
4. Tap **Deny**. It should change to "Sending denial…" and then show the proposals plugin's own answer, such as "no
   such proposal / not pending". If it says "Couldn't send", the gateway refused the command: report the exact
   wording. No chat message from the model should appear.
5. Repeat step 2 for a new message, lock the phone, and pull the notification down on the lock screen: **Approve**
   must ask for the fingerprint/PIN first and then send; if it is somehow sent without it, report that at once. If
   "Unlock your phone to approve" appears *after* a successful unlock, the lock state is read too early and Approve
   would never work from the lock screen: report it, don't retry.
6. Failure case: turn Wi-Fi and mobile data off, tap Deny: "Couldn't send. Open Hikyaku to answer." stays up.
7. Once the plugin writes the marker, a real pending proposal shows the same buttons; answering it from the phone must
   close it on the desk gadget later (P1 board part, not built).

## Buttons inside the chat (1.4.5)

Under any message whose **first line** has `[proposal:N]`, the chat shows **Approve** and **Deny**, as Telegram does.
This covers a reply of the bot and a **delivery** (the proposals job's output reaches the chat as a routine card, not as
a reply). Never while the message is still being written, and never under the person's own words.

* **Same path and safeguards as the notification:** `BotsApi.runBotCommand` (`session.resume`, `slash.exec`), never a
  prompt. Approve is refused while the phone is locked (the chat cannot show over the lock screen, so this holds
  the same guarantee as the notification's unlock); Deny needs none. One answer per proposal, from either button or
  place. A failed send shows an error and leaves the buttons.
* **After an answer** the buttons are replaced by the plugin's own message, or "Approved" / "Denied" when it printed
  nothing. The answer is **kept** (one shared, persisted ledger, keyed `profile/number`, newest 200) and shows again after
  the chat is reopened or the app restarted. The notification's buttons use the same ledger, so an answer in either
  place shows in the other, and a late tap on an old notification says "Already answered: ...".
* **Hidden:** `[proposal:N]` and `[proposal:N:word]` are removed from the text shown (also in Copy, Read aloud and
  comments). Chat-list previews and the notification shade may still show the raw text.
* **Answered elsewhere, what the app can see:** this phone (notification or chat); a `/proposal yes|no N` typed in this
  app's composer; and a later **delivery of the proposals job** (the routine card, never a reply of the model, another
  bot or a notice, since those could quote or invent one and an ending is kept and shown as fact) carrying a **closing
  marker**: `[proposal:N:approved]`, `[proposal:N:denied]`, `[proposal:N:done]` or another word such as `expired`,
  shown as that word. The board, Telegram or another client can only be seen through that closing marker, **which the
  plugin does not write yet** (a change to the plugin). Without it, the first tap there is refused by the
  plugin and its message is kept as the result.
* **Old questions:** a message older than 72 hours (the plugin's longest lifetime) shows "Expired" instead of buttons,
  by its time. Until the plugin writes closing markers, a question from the last 72 hours that was answered elsewhere
  still shows the buttons, and the plugin refuses a tap on it.
* **The notification keeps its earlier rule:** the marker may be anywhere in its text (it is the chat that needs the
  first line). Unchanged from 1.4.4, which was seen working.
* **Not tested on a phone** (no Android UI test setup): how it looks, the real delivery card, a reopened chat.
