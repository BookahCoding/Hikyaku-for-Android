# hikyaku-push: plugin improvements (0.7 → 0.9)

**Status:** design approved in conversation 2026-11-03; awaiting written-spec review.
**Related:** [push protocol](../../push-protocol.md), [proactive roadmap](../../ROADMAP.md).

## 1. Intent

Hikyaku's server half is the Hermes plugin `herald-push` (today `BookahCoding/hermes-herald-push`, a fork of
`Giton22/hermes-herald-push`, v0.6.0). It must become a more reliable base, then gain the two things the
proactive roadmap needs: a phone-to-gateway channel and a server-side event/watch engine.

Success means: pushes survive a slow or failing ntfy server; a phone can act from a notification while off the
tailnet; the gateway records and replays structured events; and at every step old apps and old plugins keep
working.

Non-goals: rewriting the crypto or wire envelope, changing the plugin's internal identity, touching the Asus
gateway without separate approval, any app (Android) change in this effort except a later pinned-URL switch.

## 2. Repo and compatibility

- New public repo `BookahCoding/hikyaku-push`, created from a **mirror of the current repo with full history**
  (Giton22's authorship and the MIT license/copyright preserved; README credits the origin).
- The fork `BookahCoding/hermes-herald-push` is left untouched and is never deleted.
- The plugin's **internal identity stays `herald-push`**: slash command `/herald-push`, data dir
  `plugin-data/herald-push`, config key `plugins.entries.herald-push`. Renaming would drop existing phone
  registrations, keys and per-bot settings.
- The app switches its install URL and pinned commit to `hikyaku-push` in a separate Hikyaku release, after the
  first tagged release of the new repo.
- All changes are additive. New features are announced through the existing `features` list in `info`/`register`
  answers; the app checks features, not version numbers.

## 3. Release 0.7: reliability (fully specified)

### 3.1 Problems (from reading v0.6.0)
1. `push.publish` is synchronous inside `Watcher.poll`; devices are sent to one after another with a 15 s
   timeout, so one hung ntfy server delays every push and cron notice for all phones.
2. A failed publish returns `False`, is logged, and the read mark advances anyway: the push is lost.
3. Every 3 s each profile's `state.db` is opened (`bot_chat_tip`) even when nothing changed.
4. `commands.py` repeats a hand-rolled `op <json>` parse block per handler.

### 3.2 Design
- **Send queue.** `poll` only enqueues `(device, payload)`. One worker per device sends with a short timeout
  (connect 5 s, total 10 s). A dead server delays only the phones that use it. Order per device is preserved.
- **Outbox.** A failed send is persisted (private file in `plugin-data/herald-push/`, mode 0600, same locking
  helper as `store.py`) with attempt count and next-try time. Backoff 5 s, 30 s, 5 min, then hourly. Entries older
  than 24 h are dropped (the phone drops older messages anyway); per-device cap (default 200, oldest dropped
  first). On start the leader loads the outbox and resumes.
- **Idempotency and privacy.** The envelope is sealed once, when queued, and the outbox stores that sealed body
  (reply text never touches the disk in the clear). A retry re-posts the same body, so the message `id` is the
  same and the phone's 24 h duplicate filter absorbs any double delivery. If the phone has re-registered since
  (its push topic changed), the stale item is dropped.
- **Cheaper polling.** Skip reading a profile's `state.db` when the modification time of `state.db` and its `-wal`
  file is unchanged since the last poll. A full read still happens at least every 60 s as a safety net.
- **Command router.** One dispatcher with a handler table and a shared JSON-argument parser. The wire format of
  `/herald-push` (arguments, JSON answers, error strings, size limits, `trusted_surface` check) is unchanged;
  existing tests pin it.
- **Feature flag.** `features` gains `reliable_push` (informational).
- **Version.** Bumped to 0.7.0 in `plugin.yaml` and `plugin.py`; existing tests already fail if the two differ.

### 3.3 Testing
- The existing pytest suite stays green before every merge.
- TDD for each new unit (send queue, outbox, router).
- Failure injection: hung server (other phones still served), HTTP 500 and 4xx classification, process killed mid-send, restart with a non-empty outbox, outbox cap,
  expiry after 24 h, duplicate delivery.
- Wire-format vectors (`tests/make_vector.py`) prove the envelope is byte-compatible, so the current app still
  decrypts everything.
- Staged install on the real gateway plus a `test` push to the phone, only with the user's separate approval.

## 4. Release 0.8: phone-to-gateway (design after spike)

Goal: the plugin reads each device's `reply_topic` (outbound streaming subscription from the leader process),
verifies the signature against that device's `sig_pub`, enforces the 10-minute window and the 24 h id memory, and
accepts `reply` (post text into the bot's Bot Chat) and `approval_answer` (once per request id, only for a request
pushed to that device, still pending and unexpired). It also starts pushing real `approval` messages, which the
protocol documents but the plugin never sends.

**Feasibility spike first (read-only against the Hermes source):**
1. Is there a supported hook to observe pending approvals and to answer one?
2. What is the supported way to append a user message to a profile's Bot Chat from a plugin?
3. Does ntfy streaming (`/<topic>/json`) work reliably with the configured server and from the leader process?

If 1 or 2 has no supported answer, 0.8 is reduced to what is possible and the gap is reported rather than worked
around by patching Hermes.

## 5. Release 0.9: events and watches (design after spike)

Goal: a SQLite event log in `plugin-data` (versioned envelope, UUID ids, monotonic cursors, 30-day retention,
idempotent ingest), a watch engine running as a second loop on the existing leader thread (not Hermes cron, to
avoid LLM cost for deterministic checks), and a `proactive` command namespace (capabilities, events list/read/ack,
watches CRUD/run). Reads follow the `cron inbox` pattern; writes require the phone's secret `key`, as `phone`
commands do. Watch types v1: `cron_job_failed`, `disk_space_threshold`, `service_unreachable` (allowlisted
targets). Condition state is tri-state; notifications fire on transitions only, with a cooldown.

Known limit to document: watches only run while the dashboard or messaging gateway process is alive.

**Spike first:** can a plugin start an agent run (needed for bot hand-off)? The only candidate found so far is a
one-shot cron job; unverified. Hand-off ships only if a supported route exists.

## 6. Order and gates

1. Create repo `hikyaku-push` (mirror with history), README, CI running pytest. Needs a go-ahead at that step.
2. Implement 0.7 from its own plan; tag `0.7.0` only with green CI.
3. Spike for 0.8; spec and plan; implement; tag.
4. Spike for 0.9; spec and plan; implement; tag.
5. Separate Hikyaku release switching the pinned plugin URL and commit.

## 7. Open risks
- 0.8 and 0.9 depend on Hermes surfaces not yet verified (approval hook, chat-append, run-start).
- Leader-only execution means watches pause when no long-lived Hermes process runs.
- Changing the app's pinned URL touches every user's install flow; it needs its own release and migration note.
