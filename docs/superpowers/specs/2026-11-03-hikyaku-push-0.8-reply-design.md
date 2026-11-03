# hikyaku-push 0.8: approval notifications

**Status:** DRAFT for review (2026-11-03). Parent spec: [hikyaku-push plugin design](2026-11-03-hikyaku-push-plugin-design.md). Evidence: [feasibility spike](2026-11-03-hikyaku-push-0.8-feasibility-spike.md).
**Supersedes** the earlier draft of this file, "reply from a notification", which the gateway spike ruled out (section 7). The file name is kept so existing links work.

## 1. Intent

When a bot is blocked waiting for the user to approve a command, the phone should say so wherever it is. The notification names the bot and what it wants to do. Tapping it opens the bot's chat in Hikyaku, where the user approves as today.

Success: while a bot waits for approval, the phone shows one notification within a few seconds, with no secrets in it, even when the phone is off the tailnet. Old apps ignore the message; old plugins simply send nothing.

## 2. Non-goals

- Answering the approval from the notification. A plugin approval transport replaces every other approval surface; the host's internal resolve function is private and bound to one process's memory.
- Replying to a bot from a notification. Both ways to do it were tried on the gateway and rejected (section 7).
- Any change to the wire envelope or to the plugin's internal identity (`herald-push`).

## 3. Plugin 0.8.0

- **Hook:** `register(ctx)` registers an observer with `ctx.register_hook("pre_approval_request", handler)` in every process that loads the plugin. The host calls it, with return values ignored, in the process where the approval is happening (the dashboard for an app chat, the messaging gateway for a Telegram one). The handler gets `command`, `description`, `pattern_key`, `session_key`, `surface`.
- **Never block the agent.** The handler builds the message and hands it to a daemon thread, then returns. The thread publishes with the plugin's normal sealing (`push.envelope_for` and `push.post`) and a 10 s timeout. There is no outbox here: the hook may run in a process without the watcher's leader, and an approval is only useful for minutes. A failed post is logged once with the profile and the outcome, and not retried.
- **Which phones:** the profile is the plugin's own profile (the manager's home). Each registered device is sent the push only if the profile is on its allowlist, exactly as for `bot_message`. The per-profile "Send notifications" switch is respected.
- **Message:** the existing `approval` type: `bot`, `label` (the bot's display name), `session` (`session_key`), `command` (redacted, at most 160 characters), `description` (at most 240), `request_id` (a short hash, for de-duplication only). No `choices` and no `expires`: nothing here can be answered.
- **Redaction:** `command` goes through Hermes' own `agent.redact.redact_sensitive_text(..., force=True)`. If that import fails, `command` is left out and only `description` is sent.
- **De-duplication:** the same approval seen twice within 60 s (several plugin managers in one process) is pushed once, keyed by a hash of `session_key`, `pattern_key` and `command`. The key is kept in memory only.
- **Advertising:** `info` and `register` answers gain the feature `approval_push`. It is informational: the app does not depend on it.
- **Version** 0.8.0 in `plugin.yaml` and `plugin.py`; README and CHANGELOG describe the feature and its limits.

## 4. Phone (Hikyaku 1.3.0, a separate release)

- `PushListener` handles `PushMessage.APPROVAL`: a notification titled "<Bot> asks for approval" with the description and the redacted command, on its own notification channel ("Approvals") so the user can silence it without losing chat notifications. It respects the profile allowlist like every other push.
- Tapping it opens the bot's chat at the stored `session`. There are no action buttons.
- A message older than 10 minutes is not shown (the approval has most likely timed out).
- The push log records it so the Inbox shows it.
- Tests in `shared/core` for parsing and in the app's push tests for the notification; no change to the crypto or to registration.

## 5. Safety

- Nothing from an approval can be acted on from the notification, so a forged or replayed `approval` can at worst show a misleading line; envelopes are signed by the pinned gateway key as for every other push.
- The command text is redacted by the host, clipped, and encrypted end to end; ntfy only sees an opaque envelope.
- The hook is an observer: it cannot veto, delay or auto-approve anything, and an exception in it is caught and logged, never raised into the agent.

## 6. Testing and order

- Plugin: a fake context records the registered hook; the handler returns at once and a fake `post` receives the sealed message; redaction on and off; clipping; the profile allowlist and the per-profile switch; de-duplication; a failing post is logged and swallowed; the existing suite and the wire vectors stay green.
- Phone: parsing of the `approval` message with and without optional fields; allowlist; stale message dropped.
- Order, each step with its own approval where it touches the gateway or ships a release: (1) plan for 0.8.0; (2) implement and release in `hikyaku-push`; (3) staged install on the gateway; (4) app 1.3.0 PR and release; (5) live check: trigger an approval from a chat and watch the phone.

## 7. Rejected approaches (so they are not retried blindly)

| Approach | Why it fails |
|---|---|
| `ctx.inject_message` for replies | Tested on the gateway: returns False unless the Bot Chat is open in the app, which is never the case when a notification reply is sent |
| Resume the Bot Chat with a one-shot `hermes chat --resume` | The reply works, but the run compacts the session, switches off its 17 active messages and marks it ended (`cli_close`); it had to be repaired from a backup |
| `register_approval_transport` for answering | A selected transport replaces every approval surface (Telegram, CLI, in-app) |
| `tools.approval.resolve_gateway_approval` | Private host function; the pending queue is in-memory per process |

Revisit replies and approval answers if Hermes adds a supported way for a plugin to send a message to a stored session or to answer an approval.

## 8. Risks

- The hook runs in several processes; a push per process is possible if de-duplication misses (different `command` text in two managers). The cost is a duplicate notification, not a wrong action.
- `agent.redact` is a host module, not a documented plugin API; the fallback (description only) keeps the feature safe if it moves.
- The approval timeout is not in the hook's arguments, so the phone cannot show a countdown.
