# hikyaku-push 0.8: feasibility spike

**Date:** 2026-11-03. **Method:** read-only. Hermes source on BookahPC (`C:\Users\lecyt\AppData\Local\hermes\hermes-agent`, commit `4d04303818`), a grep of the gateway's source (bookah-asus, Hermes v0.22.6, `f0d94882e89a`), and one live ntfy streaming test on a throwaway topic. Nothing was written to Hermes.

**Question:** can a plugin (1) observe and answer a pending approval, (2) post a user message into a profile's Bot Chat, (3) keep an outbound ntfy streaming subscription alive?

## Findings

1. **Approvals: observing is supported; answering from a plugin is not, safely.**
   - Observer hooks `pre_approval_request` and `post_approval_response` exist. Kwargs: `command`, `description`, `pattern_key(s)`, `session_key`, `surface`; the response adds `choice` and `decided_by`. Return values are ignored (veto goes through `pre_tool_call`). These are enough to **push an `approval` message to the phone**.
   - **Answering** an approval is only offered through `ctx.register_approval_transport(name, present_fn)` (see the verified limits below). The host hands over an immutable, redacted `ApprovalRequest` (`request_id`, `digest`, `allowed_choices`, `timeout_seconds`) and takes back a correlated `ApprovalDecision`; it validates scope and binding and fails closed on timeout. It is inactive until `security.approval.transport: <name>` selects it.
   - **Verified (follow-up read of `tools/approval_prompt.py`, `tools/approval_context.py`, `config_defaults.py`):** a selected transport **replaces every built-in prompt surface** (CLI, TUI, gateway/Telegram, ACP, and the in-app cards). It is a single config value, `security.approval.transport`, default `builtin`. A timeout or error denies, unless `transport_fallback: builtin` is also set. So a transport is **not** a way to *add* a phone path: selecting it would stop approvals appearing in Telegram and in Hikyaku. **Rejected for 0.8.**
   - **A second route, also not recommended:** the host function `tools.approval.resolve_gateway_approval(session_key, choice, request_id=…)` resolves a pending approval, and `get_pending_gateway_approval(session_key)` reads it. It is host-internal, not a documented plugin API, so a Hermes update can break it. The queues are also **in-memory per process**: an app chat's approval lives in the dashboard process, a Telegram one in the gateway process, while the plugin's leader is only one of the two. An answer from the phone would have to be handed to whichever process owns the session.
2. **Posting into a chat: supported.** `ctx.inject_message(content, role="user", session_key=… | origin=…)` queues a message for a session or a chat in the plugin's own profile and returns whether a host accepted it. For gateway sessions it needs `allow_gateway_injection: true` in the plugin's settings entry in the profile's `config.yaml`. On bookah-asus that file is locked (`chattr +i`), so enabling it needs Chris's explicit yes.
3. **ntfy streaming: works.** `GET /<topic>/json` delivered an `open` event and both messages live within the test. Not tested: long idle periods, keepalive and reconnect behaviour. The listener needs a reconnect loop with backoff and a "since" cursor to avoid gaps.

## Consequences for 0.8

- `reply` (a phone message into the Bot Chat) is feasible through `inject_message`, once the gateway setting is switched on. The ntfy reply listener belongs in the leader thread, with reconnect and a cursor.
- Pushing the `approval` message is feasible with the observer hooks alone and needs no config change. Tapping it opens Hikyaku, which answers over its normal connection as today.
- `approval_answer` **from a notification while off the network is not recommended for 0.8**: the only supported mechanism (a transport) would turn off every other approval surface, and the alternative leans on a private host function with a per-process queue. Revisit if Hermes adds a supported way for a plugin to answer an approval.
- Suggested 0.8 scope: the reply listener and `reply`, plus the pushed `approval` notification. No `approval_answer`.

## Gateway spike result (2026-11-03, bookah-asus, Hermes v0.22.6)

A throwaway plugin (since removed; config restored byte for byte from a backup) called `ctx.inject_message(text, "user", session_key=<default profile's Bot Chat id>)` from a thread, with `plugins.entries.<plugin>.allow_gateway_injection: true`.

| Case | Process | Result |
|---|---|---|
| Hikyaku connected, **Bot Chat not open** (Test A) | dashboard (`tui_injector: true`) | `inject_message` returned **False**, nothing injected |
| Bot Chat **open in the app** (Test B) | dashboard | returned **True**; the message appeared in the chat and the bot answered within seconds |
| either | gateway process | no TUI injector (`gateway_injector: true` only); not usable for app chats |

**Conclusion:** injection reaches a Bot Chat only while that chat is live in the dashboard process, i.e. open in the app. A notification reply is by definition sent when the app is closed, so `inject_message` cannot carry it. Per the 0.8 spec (section 8) the reply feature is **not built on `inject_message`**; the approval notification stays in scope.

**Open alternative, not yet tested:** a stored session can be continued by a one-shot `hermes -p <bot> chat` process (the watcher already notes that Bot Chat replies are sometimes written by such a child, e.g. for `message_agent`). If a one-shot turn can resume the Bot Chat session by id with a given user message, the plugin could start it as a subprocess instead of injecting. That needs its own check, on the gateway, because it runs a real agent turn.

Side findings: the default profile's `config.yaml` and `.env` were **not** immutable (`lsattr` showed no `i` flag) on 2026-11-03, contrary to the machine notes; and `hermes plugins enable <name>` plus `hermes config set plugins.entries.<name>.allow_gateway_injection true` work as the supported way to change plugin settings.
