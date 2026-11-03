# hikyaku-push 0.8: feasibility spike

**Date:** 2026-11-03. **Method:** read-only. Hermes source on BookahPC (`C:\Users\lecyt\AppData\Local\hermes\hermes-agent`, commit `4d04303818`), a grep of the gateway's source (bookah-asus, Hermes v0.22.6, `f0d94882e89a`), and one live ntfy streaming test on a throwaway topic. Nothing was written to Hermes.

**Question:** can a plugin (1) observe and answer a pending approval, (2) post a user message into a profile's Bot Chat, (3) keep an outbound ntfy streaming subscription alive?

## Findings

1. **Approvals: supported, with a design choice to make.**
   - Observer hooks `pre_approval_request` and `post_approval_response` exist. Kwargs: `command`, `description`, `pattern_key(s)`, `session_key`, `surface`; the response adds `choice` and `decided_by`. Return values are ignored (veto goes through `pre_tool_call`). These are enough to **push an `approval` message to the phone**.
   - **Answering** an approval is done through `ctx.register_approval_transport(name, present_fn)`. The host hands over an immutable, redacted `ApprovalRequest` (`request_id`, `digest`, `allowed_choices`, `timeout_seconds`) and takes back a correlated `ApprovalDecision`; it validates scope and binding and fails closed on timeout. It is inactive until `security.approval.transport: <name>` selects it.
   - **Not verified:** whether selecting a plugin transport replaces the built-in presentation. If it does, the in-app approval cards would stop working while the plugin transport is selected. The config key was not found in `hermes_cli/config.py`. This needs a read of the approval dispatch code before 0.8 is designed.
2. **Posting into a chat: supported.** `ctx.inject_message(content, role="user", session_key=… | origin=…)` queues a message for a session or a chat in the plugin's own profile and returns whether a host accepted it. For gateway sessions it needs `allow_gateway_injection: true` in the plugin's settings entry in the profile's `config.yaml`. On bookah-asus that file is locked (`chattr +i`), so enabling it needs Chris's explicit yes.
3. **ntfy streaming: works.** `GET /<topic>/json` delivered an `open` event and both messages live within the test. Not tested: long idle periods, keepalive and reconnect behaviour. The listener needs a reconnect loop with backoff and a "since" cursor to avoid gaps.

## Consequences for 0.8

- `reply` (a phone message into the Bot Chat) is feasible through `inject_message`, once the gateway setting is switched on.
- Pushing the `approval` message is feasible with the observer hooks alone and needs no config change.
- `approval_answer` is feasible only through the approval-transport route. Before designing it, check what selecting a transport does to the existing in-app approval flow.
- The ntfy reply listener belongs in the leader thread, with reconnect and a cursor.
