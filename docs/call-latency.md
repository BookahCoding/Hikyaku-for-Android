# Answering a call: from the tap to the first words

When the agent rings the phone (`phone_call_me`) and you answer, Hikyaku speaks the opening line itself ("Hi, it's
Shisha. <the reason>"). This note says where the time went, what changed, and what has and has not been checked.

## Where the time goes (read from the code, v1.0.0)

After the answer the chained voice did these in order, each one waiting for the one before it:

1. Unlock the phone (when locked) and, once, allow the microphone. Not changeable: it is the lock screen.
2. `WatchRelay.chatForCall`: connect to the gateway, open or create the chat, wait for its history.
3. `VoiceController.startLive`: ask the gateway `GET /api/audio/voice-live/status` whether the profile uses GPT-Live,
   *before* falling back to the chained voice, even for a profile that never uses it.
4. `converseChained` → `speak` → `voice`: `POST /api/audio/speak` for the first piece of the opening, and wait for the
   whole clip (the gateway's text-to-speech runs to the end of the piece before it answers).
5. Play it.

Steps 3 and 4 are network round trips and a synthesis that depend only on what is already known while the phone rings
(the reason, the gateway, the profile). That is the part that was moved.

These are source-confirmed contributors. **No real timing was taken on a phone for v1.0.0**, so there is no measured
"before" number; the "after" can only be read from the app itself (below).

## What changed (app only; no gateway or plugin change)

* `CallOpenerPrefetch` / `PreparedOpener` (`core/phone/CallOpener.kt`): when a call the agent placed starts ringing,
  the app asks the gateway, in the background, (a) the profile's voice mode and (b) for the first piece of the opening
  line (`speechChunks(...)` first piece, at most about 160 characters), using the same gateway, profile, voice and
  provider the call will use. Nothing is played: it only makes the audio.
* On answer, `VoiceController` uses that clip for the first piece instead of asking again, and skips the second
  voice-mode question. The remaining pieces follow exactly as before (the next piece is made while the first plays).
* A profile on GPT-Live is never synthesized for: the ring-time check says so and no text-to-speech is asked.
* `CallClock` and `CallTiming` record, on a monotonic clock, ring → tap → call screen open ("answered") → unlocked →
  chat ready → mode known → opening requested → first clip ready → playback started. Only stage names and
  milliseconds, never the words. They go to logcat (tag `CallTiming`) and the last call shows in Settings → Phone
  tools → Calls → "Last call": the headline "answer to first words N ms", then what each stage took.
  "Answer" starts at the tap on the lock screen's Answer button (`IncomingCallActivity`), which includes the unlock.
  An Answer from the notification shade launches the call screen directly, so for those the clock starts when that
  screen opens, a few tens of milliseconds after the tap.

## Rules it keeps

* Never plays before the answer: the preparation only produces audio; playback is in the answered call.
* One preparation per call, used at most once, bound to that call's gateway, profile and exact text
  (`PreparedOpener.matches`); anything else is ignored and the call asks the gateway itself.
* Declined, rung out, given up, replaced or ended without being taken: cancelled (`ChatNotifications.ringForAgent`),
  dropped with the call (`PhoneCallModel.onCleared`), and in any case after two minutes.
* At most two preparations at once; a third ring simply answers the usual way. One clip is held, never a cache.
* If it is not ready on answer, the call waits for the same request (no second synthesis); if it failed, the call
  asks once itself, as before.
* Profile allowlists, lock-screen answering, audio routing and the microphone are untouched. A call answered on the
  watch does not use it and is unchanged.

## Tests (automated)

* `CallOpenerTest` (core, 14): ready on answer, answer before ready (one request), once only, only the first piece,
  failure, cancel/decline/replace, time limit, GPT-Live makes no speech, other gateway/profile/text, profile used for
  the requests, cap of two, no gateway, the clock.
* `CallOpeningTest` (ui, 8): through `VoiceController`: ready on answer (one synthesis, played once, the mode asked for
  once), answer before ready, failed preparation, cancelled preparation, no preparation, other profile, other reason,
  a long opening of several pieces. Breaking each safeguard on purpose made the matching test fail.
* The Android notifier (`ChatNotifications.ringForAgent`: cancel unless answered) has no unit tests (the app module has
  no test setup); it is covered by review and the build only.

Automated timing tests prove ordering and request counts, not how long a phone takes.

## Scope

* Only calls the agent places (`phone_call_me`) are prepared. "Ring me for an answer" rings, whose question is read
  from the chat, are unchanged.
* A call answered on the watch is not prepared for; the phone's preparation is not cancelled by it and simply expires
  after two minutes (nothing plays it).

## Still to check on a phone

1. Locked phone: ring, tap Answer, unlock. Then Settings → Phone tools → Calls → "Last call": note the headline and
   the stage times, and whether the first words come right after "chatReady".
2. Unlocked phone: the same, answering from the notification and from the full-screen call.
3. Decline, then let one ring out, then a call with Tailscale off: nothing plays, no sound after the ring ends.
4. A second call while one is ringing: the first is not played for the second.
5. The watch rings too: answer on the watch, and then on the phone, with no double playback.
6. Settings → About → "Check for updates now" against v1.0.0: "up to date" when it is the newest release.
