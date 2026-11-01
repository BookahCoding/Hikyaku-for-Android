# Cron deliveries you can find and reply to — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every push Herald receives is findable afterwards, cron runs can be replied to from the Inbox, cron output reads as a card in the chat, and `/heartbeat` failures say why.

**Architecture:** A new `PushLogStore` (core, per-gateway, modelled on `InboxStore`) records accepted pushes; `PushListener` writes to it; the Inbox page gets a Messages view over it. Reply on a cron run reuses `ChatLinks.shareToBot`, which already opens a bot's Bot Chat with a draft in its composer. Cron cards replace the one-line `TranscriptEvent.Routine` view. No new Hermes method is used.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx.serialization, kotlin.test + `runTest`.

**Spec:** `docs/superpowers/specs/2026-11-01-cron-inbox-replies-design.md`

## Global Constraints

- Core logic lives in `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/…` with tests in `shared/core/src/commonTest/…`; UI in `shared/ui/src/commonMain/…`; Android glue in `androidApp/src/main/kotlin/dev/hermeskotlin/android/…`.
- Work on branch `feat/cron-inbox-replies`, cut from `spec/cron-inbox-replies`. Never push to `master` directly.
- The push log keeps at most 500 entries, 30 days, 2000 characters of text each (spec: "last 500 entries or 30 days").
- Treat gateway text as plain text (bot-mode-integration.md §3.9): no markup interpretation of bot names or labels.
- Reply never invents a bot: a profile that is not a bot the user made opens as an ordinary chat (existing `openBotLink` behaviour).
- Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u`.
- Run Gradle with: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"` then `./gradlew … --offline -q`.

**Spec deviations (decided while planning):**
1. `cron_run` pushes are already kept by `InboxStore`, so the push log records `bot_message` and `phone_call` only.
2. The spec's "map `/heartbeat` to a cron job" is reduced to a clear failure message (Task 6). The live gateway now carries a local fix for the bug (upstream issue NousResearch/hermes-agent#147596), so a real heartbeat works; the cron mapping would only help unpatched gateways.
3. The chat card shows the job name and first lines only, because TranscriptEvent.Routine has no time or status.

## Review Focus

- A push whose `session` or `bot` has odd characters or is empty: must not be logged (matches the listener's own `BOT_NAME` / `SESSION_ID` checks). Test in Task 1.
- The same push id arriving twice, or 600 pushes in a row: the log must stay at 500, newest first, no duplicates. Test in Task 1.
- A push older than 30 days (clock skew or a replayed backlog): must be dropped, not shown first. Test in Task 1.
- A cron run with a 20,000-character body, or an empty body: the reply draft must stay short and never throw. Test in Task 4.
- A cron run whose job name has quote or markup characters, or blank lines at the start: the draft shows it as plain text, quote lines start with `> `. Test in Task 4.

---

### Task 1: PushLogStore (core)

**Files:**
- Create: `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/push/PushLog.kt`
- Create: `shared/core/src/commonTest/kotlin/dev/hermeskotlin/core/push/PushLogTest.kt`

**Interfaces:**
- Produces: `PushLogEntry(id, type, at, bot, label, session, text)`, `PushLogEntry.from(message: PushMessage): PushLogEntry?`, `PushLogStore(store: KeyValueStore, work: CoroutineContext = Dispatchers.Default)` with `state: StateFlow<PushLogStore.Snapshot>`, `bind(url)`, `record(url, entry, now)`, `markRead(url, id)`, `markAllRead(url)`, `forget(url)`, and `PushLogStore.trim(entries, now)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushLogTest {
    private val url = "https://gw.example"
    private val now = 2_000_000_000L

    private fun entry(id: String, at: Long = now, text: String = "hi") =
        PushLogEntry(id = id, type = PushMessage.BOT_MESSAGE, at = at, bot = "default", session = "20261027_121247_35c8cf", text = text)

    private fun message(type: String = PushMessage.BOT_MESSAGE, bot: String? = "default", session: String? = "20261027_121247_35c8cf", text: String? = "hello") =
        PushMessage(id = "m1", ts = now, type = type, bot = bot, session = session, text = text)

    @Test
    fun aBotMessageBecomesAnEntry() {
        val e = assertNotNull(PushLogEntry.from(message()))
        assertEquals("m1", e.id)
        assertEquals("hello", e.text)
    }

    @Test
    fun nudgesAndPingsAreNotLogged() {
        assertNull(PushLogEntry.from(message(type = PushMessage.PING)))
        assertNull(PushLogEntry.from(message(type = PushMessage.PHONE_REQUEST)))
        assertNull(PushLogEntry.from(message(type = PushMessage.CRON_RUN)))
    }

    @Test
    fun oddBotOrSessionNamesAreNotLogged() {
        assertNull(PushLogEntry.from(message(bot = "bad name!")))
        assertNull(PushLogEntry.from(message(bot = "")))
        assertNull(PushLogEntry.from(message(session = "has space")))
        assertNull(PushLogEntry.from(message(text = "   ")))
    }

    @Test
    fun aVeryLongTextIsCut() {
        val e = assertNotNull(PushLogEntry.from(message(text = "x".repeat(10_000))))
        assertEquals(PushLogStore.MAX_TEXT, e.text.length)
    }

    @Test
    fun theSameIdReplacesItsEarlierEntry() = runTest {
        val log = PushLogStore(InMemoryKeyValueStore())
        log.bind(url)
        log.record(url, entry("a", text = "first"), now)
        log.record(url, entry("a", text = "second"), now)
        assertEquals(listOf("second"), log.state.value.entries.map { it.text })
    }

    @Test
    fun sixHundredPushesKeepTheNewestFiveHundred() = runTest {
        val log = PushLogStore(InMemoryKeyValueStore())
        log.bind(url)
        (1..600).forEach { log.record(url, entry("id$it", at = now - 600 + it), now) }
        val ids = log.state.value.entries.map { it.id }
        assertEquals(500, ids.size)
        assertEquals("id600", ids.first())
        assertEquals("id101", ids.last())
    }

    @Test
    fun entriesOlderThanThirtyDaysAreDropped() {
        val old = entry("old", at = now - PushLogStore.MAX_AGE_SECONDS - 1)
        val fresh = entry("fresh", at = now - 10)
        assertEquals(listOf("fresh"), PushLogStore.trim(listOf(old, fresh), now).map { it.id })
    }

    @Test
    fun eachGatewayKeepsItsOwnLogAndReadMarks() = runTest {
        val kv = InMemoryKeyValueStore()
        val log = PushLogStore(kv)
        log.bind(url)
        log.record(url, entry("a"), now)
        log.record("https://other", entry("b"), now)
        assertEquals(listOf("a"), log.state.value.entries.map { it.id })
        log.markRead(url, "a")
        assertEquals(0, log.state.value.unread)
        // A new store over the same storage sees what was kept.
        val again = PushLogStore(kv)
        again.bind("https://other")
        assertEquals(listOf("b"), again.state.value.entries.map { it.id })
        assertTrue(again.state.value.unread == 1)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:core:testAndroidHostTest --tests "dev.hermeskotlin.core.push.PushLogTest" --offline -q`
Expected: FAIL (compilation error: `PushLogEntry` / `PushLogStore` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

/** One push Herald accepted, kept so it can be found again: a bot's reply or a call (cron runs live in the Inbox). */
@Serializable
data class PushLogEntry(
    val id: String,
    val type: String,
    /** When it was received, unix seconds. */
    val at: Long,
    val bot: String? = null,
    val label: String? = null,
    /** The stored session the message is in. */
    val session: String? = null,
    val text: String = "",
) {
    companion object {
        private val BOT_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val SESSION_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        /** The entry for [message], or null for a type that isn't logged or one missing what names it. */
        fun from(message: PushMessage): PushLogEntry? {
            if (message.type != PushMessage.BOT_MESSAGE && message.type != PushMessage.PHONE_CALL) return null
            val bot = message.bot?.takeIf { BOT_NAME.matches(it) }
            val session = message.session?.takeIf { SESSION_ID.matches(it) }
            if (message.type == PushMessage.BOT_MESSAGE && (bot == null || session == null)) return null
            val text = (message.text ?: message.reason).orEmpty().trim().take(PushLogStore.MAX_TEXT)
            if (text.isEmpty()) return null
            return PushLogEntry(
                id = message.id,
                type = message.type,
                at = message.ts,
                bot = bot,
                label = message.label?.filter { !it.isISOControl() }?.trim()?.take(64)?.takeIf { it.isNotEmpty() },
                session = session,
                text = text,
            )
        }
    }
}

/**
 * The pushes Herald accepted, newest first, kept per gateway so a notification can always be found again.
 * Same shape as `InboxStore`: [bind] picks the gateway shown, [record] works for any gateway without moving it.
 */
class PushLogStore(
    private val store: KeyValueStore,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    @Serializable
    private data class Saved(val entries: List<PushLogEntry> = emptyList(), val read: Set<String> = emptySet())

    data class Snapshot(val entries: List<PushLogEntry> = emptyList(), val read: Set<String> = emptySet()) {
        val unread: Int get() = entries.count { it.id !in read }
    }

    private val lock = Mutex()
    private var gateway: String? = null
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    suspend fun bind(gatewayUrl: String) = lock.withLock {
        if (gateway == gatewayUrl) return@withLock
        val saved = load(gatewayUrl)
        gateway = gatewayUrl
        _state.value = Snapshot(saved.entries, saved.read)
    }

    suspend fun record(gatewayUrl: String, entry: PushLogEntry, now: Long = getTimeMillis() / 1000) = lock.withLock {
        val saved = if (gateway == gatewayUrl) Saved(_state.value.entries, _state.value.read) else load(gatewayUrl)
        val next = saved.copy(entries = trim(listOf(entry) + saved.entries.filter { it.id != entry.id }, now))
        save(gatewayUrl, next)
        if (gateway == gatewayUrl) _state.value = Snapshot(next.entries, next.read)
    }

    suspend fun markRead(gatewayUrl: String, id: String) = edit(gatewayUrl) { s -> s.copy(read = trimRead(s.read + id)) }

    suspend fun markAllRead(gatewayUrl: String) = edit(gatewayUrl) { s -> s.copy(read = trimRead(s.read + s.entries.map { it.id })) }

    /** Forgets [gatewayUrl]'s log, when the gateway itself is forgotten. */
    suspend fun forget(gatewayUrl: String) = lock.withLock {
        store.remove(key(gatewayUrl))
        if (gateway == gatewayUrl) {
            gateway = null
            _state.value = Snapshot()
        }
    }

    private suspend fun edit(url: String, change: (Saved) -> Saved) = lock.withLock {
        if (gateway != url) return@withLock
        val next = change(Saved(_state.value.entries, _state.value.read))
        save(url, next)
        _state.value = Snapshot(next.entries, next.read)
    }

    private suspend fun load(url: String): Saved {
        val raw = store.get(key(url)) ?: return Saved()
        return withContext(work) { runCatching { HermesJson.decodeFromString(Saved.serializer(), raw) }.getOrDefault(Saved()) }
    }

    private suspend fun save(url: String, saved: Saved) {
        val encoded = withContext(work) { HermesJson.encodeToString(Saved.serializer(), saved) }
        store.put(key(url), encoded)
    }

    private fun key(url: String) = "pushlog.v1.$url"

    companion object {
        const val MAX_KEPT = 500
        const val MAX_AGE_SECONDS = 30L * 24 * 3600
        const val MAX_TEXT = 2_000
        private const val MAX_READ = 1_000

        /** [entries] without repeats or anything older than [MAX_AGE_SECONDS], newest first, at most [MAX_KEPT]. */
        fun trim(entries: List<PushLogEntry>, now: Long): List<PushLogEntry> =
            entries.filter { now - it.at <= MAX_AGE_SECONDS }.distinctBy { it.id }.sortedByDescending { it.at }.take(MAX_KEPT)

        private fun trimRead(read: Set<String>): Set<String> = if (read.size <= MAX_READ) read else read.toList().takeLast(MAX_READ).toSet()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:core:testAndroidHostTest --tests "dev.hermeskotlin.core.push.PushLogTest" --offline -q`
Expected: PASS (no output with `-q`).

- [ ] **Step 5: Commit**

```bash
git add shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/push/PushLog.kt shared/core/src/commonTest/kotlin/dev/hermeskotlin/core/push/PushLogTest.kt
git commit -m "feat: keep a log of the pushes Herald accepts" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```

---

### Task 2: Record pushes (DI + PushListener)

**Files:**
- Modify: `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/di/CoreModule.kt:160` (next to `single { InboxStore(…) }`)
- Modify: `androidApp/src/main/kotlin/dev/hermeskotlin/android/push/PushListener.kt` (constructor, `receive`)
- Modify: the place that constructs `PushListener` (find with `grep -rn "PushListener(" androidApp shared`)

**Interfaces:**
- Consumes: `PushLogStore`, `PushLogEntry.from` (Task 1).
- Produces: every accepted `bot_message` / `phone_call` of the signed-in gateway is recorded before any visibility or settings filter, so a push that showed no notification is still in the log.

- [ ] **Step 1: Register the store**

In `CoreModule.kt`, directly after `single { InboxStore(get(), gate = get<NotificationProfiles>()) }` add:

```kotlin
    single { PushLogStore(get()) }
```
and add `import dev.hermeskotlin.core.push.PushLogStore`.

- [ ] **Step 2: Inject it into PushListener**

Read `PushListener.kt` lines 1–60. Add `private val pushLog: PushLogStore,` to the constructor next to `inbox`, and add `pushLog` to the construction site found by the grep above (use `get()` if built by Koin, else pass the instance next to `inbox`).

- [ ] **Step 3: Record in `receive`**

In `receive`, directly after `store.seen = guard.snapshot()` and before `when (message.type)`, insert:

```kotlin
        gatewayUrl?.let { url ->
            PushLogEntry.from(message)
                ?.takeIf { entry -> entry.bot.let { name -> name == null || profiles.allows(url, name) } }
                ?.let { entry -> pushLog.record(url, entry) }
        }
```
Add `import dev.hermeskotlin.core.push.PushLogEntry` and `import dev.hermeskotlin.core.push.PushLogStore`.

- [ ] **Step 4: Bind the store to the shown gateway**

Find where `inbox.bind(` is called (`grep -rn "inbox.bind\|\.bind(" shared androidApp | grep -i inbox`) and call `pushLog.bind(gatewayUrl)` beside it. Where the Inbox is forgotten (`inbox.forget(`), also call `pushLog.forget(url)`.

- [ ] **Step 5: Compile**

Run: `./gradlew :androidApp:assembleDebug --offline -q`
Expected: BUILD SUCCESSFUL, no output.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: record accepted pushes in the push log" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```

---

### Task 3: Messages view in the Inbox

**Files:**
- Modify: `shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/inbox/InboxViewModel.kt` (state, `InboxActions` at ~line 163)
- Modify: `shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/inbox/InboxPage.kt` (`InboxView` at ~line 214)
- Create: `shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/inbox/MessageLogView.kt`

**Interfaces:**
- Consumes: `PushLogStore.state`, `PushLogEntry`, `ChatLinks.openBot(bot, label, storedSessionId)` (core/chat/ChatLinks.kt).
- Produces: `enum class InboxTab { Runs, Messages }`; `InboxActions.setTab(tab)`, `InboxActions.openMessage(entry)`; `MessageLogView(entries, read, nameOf, onOpen)`.

- [ ] **Step 1: Read first**

Read `InboxViewModel.kt` (constructor, `InboxUiState`, `InboxActions` and its implementation) and `InboxPage.kt` lines 129–313. Confirm how `InboxLinks` and `ChatLinks` reach the view model; if `ChatLinks` is not a constructor parameter, add `private val chatLinks: ChatLinks` and pass `get()` where the view model is created.

- [ ] **Step 2: Add the tab state and actions**

In `InboxViewModel.kt`:

```kotlin
enum class InboxTab { Runs, Messages }
```
Add to `InboxUiState`: `val tab: InboxTab = InboxTab.Runs`, `val messages: List<PushLogEntry> = emptyList()`, `val readMessages: Set<String> = emptySet()`.
Add to `InboxActions`:

```kotlin
    fun setTab(tab: InboxTab)
    fun openMessage(entry: PushLogEntry)
```
In the implementation: combine `pushLog.state` into the UI state (`messages = it.entries`, `readMessages = it.read`); `setTab` updates `tab`; and

```kotlin
    override fun openMessage(entry: PushLogEntry) {
        val bot = entry.bot ?: return
        val url = signedInGatewayUrl() ?: return   // use the same accessor the file already uses for `inbox.markRead(url, …)`
        viewModelScope.launch { pushLog.markRead(url, entry.id) }
        chatLinks.openBot(bot, entry.label, entry.session)
    }
```

- [ ] **Step 3: Write the Messages list**

`MessageLogView.kt` (copy the imports `RunCard` in `InboxPage.kt` uses for `Theme`, `typography`, `colors`, `Text`, `Column`, `clickable`, `LazyColumn`):

```kotlin
package dev.hermeskotlin.ui.inbox

// imports as in InboxPage.kt's RunCard, plus dev.hermeskotlin.core.push.PushLogEntry

@Composable
internal fun MessageLogView(
    entries: List<PushLogEntry>,
    read: Set<String>,
    nameOf: (PushLogEntry) -> String,
    onOpen: (PushLogEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            "Messages your agents push to this phone appear here, so a notification can always be found again.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textTertiary],
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(entries, key = { it.id }) { entry ->
            val unread = entry.id !in read
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = entry.bot != null) { onOpen(entry) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    nameOf(entry),
                    style = Theme[typography][bodySmall],
                    color = if (unread) Theme[colors][textColor] else Theme[colors][textSecondary],
                )
                Text(
                    entry.text,
                    style = Theme[typography][bodySmall],
                    color = Theme[colors][textTertiary],
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
```
The row shows up to six lines of the stored text, so a message stays readable even when its chat can't be opened (spec: "show the stored text instead of nothing"). `nameOf` is `entry.label ?: entry.bot ?: "Herald"`.

- [ ] **Step 4: Show it**

In `InboxView`, above the existing content, add a two-chip row `Runs | Messages` (reuse the `FilterChips` look at `InboxPage.kt:314`) bound to `state.tab`/`setTab`. When `state.tab == InboxTab.Messages`, render `MessageLogView(state.messages, state.readMessages, nameOf, viewModel::openMessage)` instead of the folder list.

- [ ] **Step 5: Compile and check by hand**

Run: `./gradlew :androidApp:assembleDebug --offline -q` → BUILD SUCCESSFUL.
Manual: install, trigger a bot reply from Shisha, confirm it appears under Inbox → Messages and tapping opens that bot's chat.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: a Messages view in the Inbox over the push log" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```

---

### Task 4: Reply on cron runs

**Files:**
- Create: `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/inbox/CronReply.kt`
- Create: `shared/core/src/commonTest/kotlin/dev/hermeskotlin/core/inbox/CronReplyTest.kt`
- Modify: `shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/inbox/InboxViewModel.kt` (add `reply`)
- Modify: `shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/inbox/InboxPage.kt:544` (`RunView`)

**Interfaces:**
- Consumes: `InboxRun` (core/inbox/Inbox.kt), `ComposeDraft(text=…)` and `ChatLinks.shareToBot(bot, draft)` (core/chat/ChatLinks.kt).
- Produces: `CronReply.text(run: InboxRun): String`, `CronReply.draft(run: InboxRun): ComposeDraft`, `InboxActions.reply(run: InboxRun)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.hermeskotlin.core.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CronReplyTest {
    private fun run(text: String, status: String = InboxRun.OK, label: String = "mail-watch") =
        InboxRun(bot = "default", job = "a5e117cbf8ac", run = "2026-11-01_17-16-21", label = label, status = status, text = text)

    @Test
    fun quotesTheFirstThreeNonBlankLines() {
        val text = CronReply.text(run("\n\nOne\nTwo\n\nThree\nFour"))
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n> One\n> Two\n> Three\n> …\n\n", text)
    }

    @Test
    fun anEmptyBodyHasNoQuote() {
        assertEquals("Re: mail-watch · 2026-11-01 17:16\n\n", CronReply.text(run("")))
    }

    @Test
    fun aHugeBodyStaysShort() {
        val text = CronReply.text(run("line ".repeat(5000)))
        assertTrue(text.length < 400, "was ${text.length}")
    }

    @Test
    fun aFailedRunSaysSo() {
        assertTrue(CronReply.text(run("boom", status = InboxRun.FAILED)).startsWith("Re: mail-watch (failed) · "))
    }

    @Test
    fun markupInTheNameStaysPlainText() {
        val text = CronReply.text(run("x", label = "**bold** <b>"))
        assertTrue(text.startsWith("Re: **bold** <b> · "))
    }

    @Test
    fun aRunNameThatIsNotATimestampIsShownAsIs() {
        val odd = InboxRun(job = "j", run = "weird", label = "j")
        assertTrue(CronReply.text(odd).startsWith("Re: j · weird"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:core:testAndroidHostTest --tests "dev.hermeskotlin.core.inbox.CronReplyTest" --offline -q`
Expected: FAIL (`CronReply` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.chat.ComposeDraft

/** What a reply to a cron run starts with: which run it is about, and the start of what it said, as plain text. */
object CronReply {
    private const val QUOTED_LINES = 3
    private const val MAX_LINE = 120

    fun text(run: InboxRun): String {
        val head = "Re: ${run.title}${if (run.failed) " (failed)" else ""} · ${whenLabel(run.run)}"
        val lines = run.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val quote = lines.take(QUOTED_LINES).map { "> ${it.take(MAX_LINE)}" } + if (lines.size > QUOTED_LINES) listOf("> …") else emptyList()
        return (listOf(head) + quote).joinToString("\n") + "\n\n"
    }

    fun draft(run: InboxRun): ComposeDraft = ComposeDraft(text = text(run))

    /** `2026-11-01_17-16-21` → `2026-11-01 17:16`; anything else as it came. */
    private fun whenLabel(run: String): String {
        val m = Regex("""^(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-\d{2}$""").matchEntire(run) ?: return run
        return "${m.groupValues[1]} ${m.groupValues[2]}:${m.groupValues[3]}"
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:core:testAndroidHostTest --tests "dev.hermeskotlin.core.inbox.CronReplyTest" --offline -q`
Expected: PASS.

- [ ] **Step 5: Wire the button**

In `InboxActions` add `fun reply(run: InboxRun)`; implement as

```kotlin
    override fun reply(run: InboxRun) {
        chatLinks.shareToBot(run.bot, CronReply.draft(run))
    }
```
In `RunView` (`InboxPage.kt:544`) add a parameter `onReply: () -> Unit` and, below the run text, a button labelled `if (run.failed) "Ask about this" else "Reply"` that calls it; pass `{ viewModel.reply(run) }` from `InboxView`. Follow the file's existing button style (look at how `MoreRow` builds its clickable row).

- [ ] **Step 6: Compile and check by hand**

Run: `./gradlew :androidApp:assembleDebug --offline -q` → BUILD SUCCESSFUL.
Manual: open a run in the Inbox, tap Reply, confirm the Bot Chat opens with the `Re: …` quote in the composer, send "thanks", and confirm Shisha answers in that chat.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: reply to a cron run from the Inbox" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```

---

### Task 5: Cron cards in the chat

**Files:**
- Create: `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/chat/RoutinePreview.kt`
- Create: `shared/core/src/commonTest/kotlin/dev/hermeskotlin/core/chat/RoutinePreviewTest.kt`
- Modify: `shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/chat/TranscriptEventViews.kt:94`

**Interfaces:**
- Consumes: `TranscriptEvent.Routine(name, body)` (core/chat/TranscriptEvents.kt:23).
- Produces: `fun TranscriptEvent.Routine.preview(lines: Int = 3): String`, `val TranscriptEvent.Routine.hasMore: Boolean`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutinePreviewTest {
    private fun routine(body: String) = TranscriptEvent.Routine("mail-watch", body)

    @Test
    fun theFirstThreeNonBlankLines() {
        assertEquals("a\nb\nc", routine("\na\n\nb\nc\nd").preview())
    }

    @Test
    fun hasMoreWhenLinesAreLeftOut() {
        assertTrue(routine("a\nb\nc\nd").hasMore)
        assertFalse(routine("a\nb").hasMore)
    }

    @Test
    fun anEmptyBodyHasNothingToShow() {
        assertEquals("", routine("   \n ").preview())
        assertFalse(routine("").hasMore)
    }

    @Test
    fun aLongLineIsCut() {
        assertTrue(routine("x".repeat(5000)).preview().length <= 300)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:core:testAndroidHostTest --tests "dev.hermeskotlin.core.chat.RoutinePreviewTest" --offline -q`
Expected: FAIL (`preview` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package dev.hermeskotlin.core.chat

private const val MAX_LINE = 100

private fun TranscriptEvent.Routine.nonBlankLines(): List<String> = body.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()

/** The start of a routine's output, shown on its card before it is opened. */
fun TranscriptEvent.Routine.preview(lines: Int = 3): String = nonBlankLines().take(lines).joinToString("\n") { it.take(MAX_LINE) }

/** Whether the card has more than [preview] shows. */
val TranscriptEvent.Routine.hasMore: Boolean get() = nonBlankLines().size > 3
```

- [ ] **Step 4: Run to verify it passes**

Run: the command from Step 2. Expected: PASS.

- [ ] **Step 5: Render the card**

In `TranscriptEventViews.kt`, replace the `is TranscriptEvent.Routine -> FoldedLine(…)` branch with `is TranscriptEvent.Routine -> RoutineCard(event)` and add, next to `FoldedLine`:

```kotlin
@Composable
private fun RoutineCard(event: TranscriptEvent.Routine) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            UnstyledIcon(Lucide.AlarmClock, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(15.dp))
            Text(
                event.name,
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val shown = if (open) event.body else event.preview()
        if (shown.isNotBlank()) Box(Modifier.padding(start = 23.dp)) { MarkdownText(shown) }
        if (event.hasMore) {
            Text(
                if (open) "Show less" else "Show all",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
                modifier = Modifier.padding(start = 23.dp).clickable { open = !open },
            )
        }
    }
}
```
Add `import dev.hermeskotlin.core.chat.hasMore` and `import dev.hermeskotlin.core.chat.preview`.

- [ ] **Step 6: Compile and check by hand**

Run: `./gradlew :androidApp:assembleDebug --offline -q` → BUILD SUCCESSFUL.
Manual: open Shisha's Bot Chat; a `[Cronjob "…" output …]` turn now shows the job name with its first lines and "Show all".

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: cron output as a readable card in the chat" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```

---

### Task 6: A clear message when `/heartbeat` and `/loop` can't run

**Files:**
- Create: `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/slash/SlashFailureHint.kt`
- Create: `shared/core/src/commonTest/kotlin/dev/hermeskotlin/core/slash/SlashFailureHintTest.kt`
- Modify: `shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/chat/ChatSession.kt:667-670` (the `SlashResult.Output` branch of `runCommand`)

**Interfaces:**
- Produces: `fun slashFailureHint(command: String, output: String): String?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.hermeskotlin.core.slash

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlashFailureHintTest {
    private val raw = "Heartbeats unavailable (no active session)."

    @Test
    fun heartbeatWithTheWorkerBugGetsAnExplanation() {
        val hint = slashFailureHint("heartbeat", raw)!!
        assertContains(hint, "/heartbeat")
        assertContains(hint, "147596")
    }

    @Test
    fun loopGetsItToo() {
        assertContains(slashFailureHint("loop", "Loops unavailable (no active session).")!!, "/loop")
    }

    @Test
    fun otherCommandsAndOtherOutputAreLeftAlone() {
        assertNull(slashFailureHint("status", raw))
        assertNull(slashFailureHint("heartbeat", "♥ Heartbeat set (every 10m): test"))
        assertEquals(null, slashFailureHint("heartbeat", ""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:core:testAndroidHostTest --tests "dev.hermeskotlin.core.slash.SlashFailureHintTest" --offline -q`
Expected: FAIL (`slashFailureHint` unresolved).

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run to verify it passes**

Run: the command from Step 2. Expected: PASS.

- [ ] **Step 5: Use it**

In `ChatSession.runCommand`, the `SlashResult.Output` branch becomes:

```kotlin
            is SlashResult.Output -> {
                val text = slashFailureHint(command.name, result.text) ?: result.text.ifBlank { "Done." }
                finishCommand(key, result.warning?.let { "$it\n\n$text" } ?: text)
                null
            }
```
and add `import dev.hermeskotlin.core.slash.slashFailureHint`.

- [ ] **Step 6: Compile and run the core tests**

Run: `./gradlew :shared:core:testAndroidHostTest --offline -q` then `./gradlew :androidApp:assembleDebug --offline -q`.
Expected: both pass. (Existing `ChatSession` tests that expect the raw "no active session" line, if any, fail here: update them to the hint.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: explain why /heartbeat and /loop can't run on an unpatched gateway" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```

---

### Task 7: Roadmap and follow-ups

**Files:**
- Modify: `docs/ROADMAP.md` (a checked line per shipped item; one unchecked line for Hikyaku)
- Modify: `docs/superpowers/specs/2026-11-01-cron-inbox-replies-design.md` (status line)

- [ ] **Step 1: Edit the roadmap**

Add under the Inbox section:

```markdown
- [x] Push log: every accepted bot message and call is kept per gateway (500 / 30 days) and listed under Inbox → Messages
- [x] Reply on a cron run: opens the bot's chat with the run quoted in the composer
- [x] Cron output shown as a card in the chat (first lines, expandable)
- [x] /heartbeat and /loop say why they can't run on a gateway with the slash-worker bug
- [ ] Hikyaku (Windows): the same push log, Reply on cron runs and cron cards
```

- [ ] **Step 2: Mark the spec**

Change `Status: awaiting review.` to `Status: implemented on feat/cron-inbox-replies (see plan of the same date).`

- [ ] **Step 3: Full test run and commit**

Run: `./gradlew :shared:core:testAndroidHostTest --offline -q` → no output.

```bash
git add -A
git commit -m "docs: roadmap and spec status for the cron inbox work" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" -m "Claude-Session: https://claude.ai/code/session_01T1LnxJHfk8FszFkvTwoX2u"
```
