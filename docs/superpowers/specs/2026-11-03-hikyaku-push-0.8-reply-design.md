# hikyaku-push 0.8: reply from a notification, approval notifications

**Status:** design approved in conversation 2026-11-03; awaiting written-spec review. Parent spec: [hikyaku-push plugin design](2026-11-03-hikyaku-push-plugin-design.md). Evidence: [feasibility spike](2026-11-03-hikyaku-push-0.8-feasibility-spike.md).

## 1. Intent

When the phone cannot reach the gateway (off the tailnet), a user should be able to **reply to a bot from its notification**, and should **hear that a bot wants approval**. Answering the approval itself stays in the app (see non-goals).

Success: a reply typed in a notification reaches the bot's Bot Chat within seconds, the bot's answer comes back through the existing push path, and the phone says plainly whether the reply was delivered. Old apps and old plugins keep working, and nothing here is offered when it cannot work.

Non-goals:
- Answering approvals from a notification. The only supported mechanism, a plugin approval transport, replaces every other approval surface (Telegram, CLI, in-app cards); the alternative is a private host function with a per-process queue.
- Changing the wire envelope or the plugin's internal identity (`herald-push`).
- Any change to the gateway without the user's separate approval.

## 2. Flow

1. The user replies in a notification. The app seals a `reply` message (`bot`, `session`, `text`; format already reserved in the protocol) to the gateway's `enc` key, signs it, and posts it to the phone's `reply_topic` on ntfy.
2. The plugin's reply listener receives it, verifies the signature against the **owning device's** pinned `sig_pub` before decrypting, checks `ts` (10 minutes) and the remembered message ids, checks the device may speak to that profile, and injects the text into the profile's Bot Chat with `ctx.inject_message`.
3. The plugin pushes a small `reply_result` (`ok` / `unavailable` / `refused`, with the reply's id) so the phone can show sent, or "open the app".
4. The bot's answer reaches the phone through the existing `bot_message` push.

## 3. Components (plugin)

- **`replies.py`** (new): the listener. Outbound streaming subscription to each registered device's `reply_topic` (`GET <server>/<topic>/json`), reconnect with backoff and a `since` cursor so a dropped connection loses nothing, one connection per distinct ntfy server.
- **Inbound check** (new, pure function): signature, `ts` window, id memory persisted on disk with 24 h expiry, device exists, profile allowed for that device (existing allowlist), `bot`/`session` well-formed, `text` non-empty and at most 2000 characters. A failure produces `refused` and nothing is injected.
- **Injector adapter** (new): one function per loaded profile, holding that profile's plugin context, calling `inject_message(text, role="user", session_key=<session>)`. Returns accepted or not.
- **Rate limit:** per device, at most 10 replies per minute; extra ones are `refused`.
- **Approval notification** (new): an observer on `pre_approval_request` pushes `approval` (`bot`, `label`, `session`, `request_id`, `command`, `description`, `choices`, `expires`) to phones allowed that profile. `command` and `description` are passed through Hermes' own redaction (`agent.redact.redact_sensitive_text`) and clipped to fit one push; if redaction is unavailable, only `description` is sent. No action is accepted for it.

## 4. Which process listens

`inject_message` queues into a **live** session and only in the process that owns it; app (dashboard) chats live in the dashboard process, while the existing watcher's leader may be the gateway process. The reply listener therefore takes its **own lock** (`replies.lock`, separate from `leader.lock`) and runs **only in a process that has a TUI message injector** (the dashboard). In any other process it does not start.

The plugin advertises the feature `reply_via_push` in `info` and `register` answers **only when** the setting `allow_gateway_injection` is true for that profile and the process can inject. The app shows the notification Reply action only for a gateway that advertises it. If injection is refused at run time (no live session for that chat), the plugin answers `reply_result: unavailable` and the phone tells the user to open the app.

## 5. Safety

- Only registered devices; each only into profiles on its allowlist. A device never replies as another device (the topic identifies the device).
- Signature before decrypt; ids remembered on disk so a restart does not accept a replay; `ts` older than 10 minutes dropped, as the protocol already says for actions.
- Reply text is treated as the user's own typed message, with the same trust as typing in the app: it is a message to a bot, not a command run by the plugin.
- Nothing from a reply is logged except its id, the profile and the outcome.
- Setting `allow_gateway_injection` on the gateway needs the user's explicit approval and a service restart; the plugin never edits config itself.

## 6. Phone side (separate release, Hikyaku 1.3.0)

The notification Reply action, a sender that seals and posts to the reply topic with the existing push crypto, and sending / sent / couldn't-send states driven by `reply_result`. Shown only when the gateway advertises `reply_via_push`. A reply that cannot be sent is kept as a draft in the app.

## 7. Testing

- Plugin: unit tests for the inbound check (bad signature, replayed id, stale `ts`, wrong profile, oversized text, rate limit), the listener's reconnect and cursor against a fake ntfy stream, the injector adapter with a fake context, the lock (second process does not listen), feature advertising on and off, and approval redaction and clipping. The existing suite stays green; wire vectors prove the envelope is unchanged.
- Cross-implementation: the plugin opens an envelope sealed by the app's `PushCrypto` (the repo already has an interop test for g2p; add p2g).
- Live: staged install on the gateway, then a real reply from the phone off Wi-Fi.

## 8. Order and gates

1. **Gateway spike (needs the user's go-ahead):** a throwaway test plugin on bookah-asus with `allow_gateway_injection` enabled for it, to find out whether `inject_message(session_key=<Bot Chat id>)` from the dashboard process works for a Bot Chat that is not open in the app. Result decides the rest:
   - works: continue with 2 to 5;
   - works only when the chat is open: ship the approval notification only, and say so;
   - does not work: ship the approval notification only.
2. Plan for plugin 0.8.0 (its own written plan), then implement and release in `hikyaku-push`.
3. Staged install on the gateway (separate approval).
4. App 1.3.0 (separate PR and release).

## 9. Risks

- Injection into a closed Bot Chat may be impossible (step 1 settles it).
- `allow_gateway_injection` widens what the plugin may do on the gateway; it is limited by the checks in section 5.
- ntfy streaming idle behaviour is untested over long periods; reconnect and cursor cover it, and a 60 s poll fallback is acceptable if streaming proves flaky.
