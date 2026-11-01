# Draft upstream issue — NousResearch/hermes-agent (not filed)

**Title:** `/heartbeat` and `/loop` report "no active session" through the TUI slash worker (v0.22.5)

**Summary**
Typing `/heartbeat every 10m <prompt>` in a Desktop/TUI-gateway chat (here from the Herald Android client, `slash.exec`)
always answers `Heartbeats unavailable (no active session).`, even in a saved chat that already has messages.

**Environment**
Hermes v0.22.5 (`bf0203a794`), dashboard/TUI gateway, Linux. Client: Herald (Android), session source `desktop`.
Reproduces on the first and later messages of a chat.

**Cause (from reading the code)**
1. `tui_gateway/methods_tools.py` runs `/heartbeat` in the session's slash worker (`slash_worker.py`).
2. The worker builds `HermesCLI(resume=session_key)`. `_init_session_store` always sets `_session_db` to
   `GatewaySessionStore()` (`gateway_session_store.session_store()`).
3. The worker never calls `_ensure_gateway_session()` (only the interactive path does, `cli_tui_runtime_mixin.py:295`,
   `cli_agent_setup_mixin.py:669`), so `_gateway_session_open` stays false.
4. `CLILoopsMixin._automation_connection()` then returns `(True, None)` and `_automation_manager()` returns `None`,
   which `_session_manager()` prints as "unavailable (no active session)".
   The docstring says the slash worker's shadow CLI should use the in-process managers; with the facade it does not.

`/loop` goes through the same `_automation_manager`, so it should fail the same way; `/goal` is not affected in my
tests because it also dispatches elsewhere (not verified further).

**Workaround that fixes it locally**
Treat the worker as local: in `_automation_connection` and `gateway_automation.gateway_connection`, return
`(False, None)` / `None` when `cli.is_slash_worker` is true. (Two small hunks; patch available.)

**Open question for maintainers**
Whether the worker should instead open its gateway session, and who fires a heartbeat set from the worker
(the docs say the owning process must be running).

**Expected:** `/heartbeat every 10m check mail` sets the heartbeat and `/heartbeat status` shows it.
**Actual:** `Heartbeats unavailable (no active session).`
