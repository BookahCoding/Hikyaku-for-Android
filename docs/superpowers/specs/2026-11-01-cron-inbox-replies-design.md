# Cron deliveries you can find and reply to — design

Date: 2026-11-01. Status: implemented on feat/cron-inbox-replies (see plan of the same date).

## Goal

Herald and the Hikyaku Windows app become the only way Chris talks to his Hermes agents (Shisha on bookah-asus).
Three outcomes:

1. Every notification Herald shows can be found again in the app, and tapping it opens that exact message.
2. A cron job's output reaches the Inbox, and Chris can reply to it so the agent answers with that output as context.
3. `/heartbeat` (and the other session-scoped commands) either work or say clearly why they don't.

## What was found (2026-11-01, read-only on bookah-asus, Hermes v0.22.5)

- Every cron job uses `deliver: bot-chat`. The output is appended to Shisha's single Bot Chat (source `desktop`,
  title `Bot Chat`, `hidden=1`) as a user turn `[Cronjob "<name>" output — scheduled job, not the user…]`, and
  Shisha replies in that chat. Replying is therefore already possible at the gateway; the app just doesn't offer it.
- The herald-push plugin pushes only final assistant replies in a Bot Chat (docs/push-protocol.md). A push for
  a reply is hard to find afterwards: the chat is hidden, shared by all jobs (`mail-watch` alone ~9 runs/day) and
  Herald collapses the cron user turns to "⏰ ran".
- Hermes compresses the Bot Chat itself (compaction seen 2026-11-01 06:05), so length is not a concern. In a Bot
  Chat `/new` and `/reset` compress rather than start fresh, by design; the normal New chat button is unaffected.
- Some jobs confuse the agent: it answered `aimesh-daily-check` output with "this message doesn't match my run".
- `/heartbeat` fails with "Heartbeats unavailable (no active session)" even in a saved chat after a first message.
  Cause (installed code): the TUI slash worker's shadow CLI uses `GatewaySessionStore` but never calls
  `_ensure_gateway_session()`, so `_gateway_session_open` stays false and `_automation_manager` returns `None`
  (`hermes_cli/cli_loops_mixin.py:589-605`, `cli_init_mixin.py:452`). It is a Hermes bug, not an app bug. No upstream issue exists.

## Design

### 1. Push log ("nothing is unfindable")

Herald stores every push it accepts (`bot_message`, `cron_run`, `approval`, `phone_call`) in a local log: id,
type, bot, session, label, text start, received-at, read flag. The Inbox gets a "Messages" view of it next to the
cron runs. Tapping a notification, or a log row, opens the Bot Chat message it names, even when the bot is hidden.

- Open `resolved_id || id` of the session so a compression chain end is used, as Bot Chat opening already does.
- If the session can't be opened, show the stored text with "couldn't open the chat" instead of nothing.
- Log is capped (last 500 entries or 30 days) and cleared with the identity when the gateway changes.

### 2. Reply on cron cards

A cron run in the Inbox gets a Reply action. It opens the bot's Bot Chat with the run attached as a quoted
reference above the composer; sending posts one user turn `Re: <job> (<time>)\n<quote start>\n\n<reply>` into
that chat. No new Hermes method is needed (`prompt.submit` into the existing Bot Chat, same as normal Bot Chat
messages). Failed runs offer "Ask Shisha about this" with the error text.

### 3. Readable cron cards in the chat

The transcript shows a `[Cronjob … output]` turn as a card: job name, time, status, first lines, expandable.
It is no longer a bare "⏰ ran" line. The agent's answer under it is shown normally. Jobs with nothing to say
should end `[SILENT]` (a Hermes job setting, listed under Gateway-side below), so no card and no push appear.

### 4. Session-scoped commands

- Until Hermes fixes the worker, Herald maps `/heartbeat every <interval> <prompt>` to a cron job
  (`deliver: bot-chat`, schedule from the interval, name `heartbeat: <prompt start>`) and `status/pause/resume/clear`
  to that job, and tells the user it did. `session.control` (`heartbeat.pause|resume|clear`) works on a real heartbeat
  and stays used for those actions.
- `/loop` and `/refine` are checked the same way; if they fail with the same worker cause, the app shows
  "Hermes can't run /loop from the app yet" instead of the raw gateway line.
- A draft upstream issue is written; filing it is Chris's call.

## Gateway-side (separate from app code, needs Chris's yes)

- Add `[SILENT]` handling to quiet jobs (`mail-watch`), and review `aimesh-*` prompts so the agent isn't told
  the output is "not the user".
- Optional local patch of the heartbeat worker bug; it is overwritten by Hermes updates.

## Windows Hikyaku app

Separate repo. It needs the same push log, Reply on cron cards and card rendering. This spec fixes the gateway
contract (above) so both clients behave alike; the Windows work gets its own plan.

## Not in this change

Per-job chats, mirroring Telegram sessions into the app, changes to Bot Chat identity rules (bot-mode-integration.md §3).

## Testing

- Core (commonTest): push-log append, cap and expiry; cron-turn parsing into a card; reply formatting; the
  `/heartbeat` → cron mapping, including odd intervals and missing prompt.
- Manual on the real gateway: a test cron job delivered to the Bot Chat; reply from the Inbox; tap a notification
  for a hidden bot; `/heartbeat every 10m …`.

## Open questions

- Which profiles get the Reply action (Magda's Φρέμπελ is separate and stays so).
- Whether the heartbeat→cron mapping should also exist in the Windows app.
