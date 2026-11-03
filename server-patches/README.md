# Server patches for hermes-agent

These patches change **hermes-agent** (the server), not the Herald app. Apply them on the machine that runs your Hermes gateway.

## slash-worker-heartbeat-v0.22.6.patch

**Targets:** hermes-agent v0.22.6

**Problem:** The TUI slash worker never opens a gateway session. The automation code still looked for one, so `/heartbeat` and `/loop` reported "no active session" when run from a slash worker.

**Fix:** When the CLI is a slash worker, use the in-process managers instead of looking for a gateway session. The change is 6 added lines and 1 removed line across two files:

- `hermes_cli/cli_loops_mixin.py` (`_automation_connection`)
- `hermes_cli/gateway_automation.py` (`gateway_connection`)

### Apply

Run these in your hermes-agent folder (the one containing `.git`):

```
git apply --check /path/to/slash-worker-heartbeat-v0.22.6.patch
git apply /path/to/slash-worker-heartbeat-v0.22.6.patch
hermes gateway restart
```

`--check` is a dry run. If it reports an error, your hermes-agent version differs from v0.22.6 and the patch needs adjusting.

### Verify

```
git diff --stat
```

You should see 2 files changed, 6 insertions(+), 1 deletion(-).

### After updating hermes-agent

`hermes update` may overwrite or conflict with these edits. Re-apply the patch after each update, then restart the gateway.

### Undo

```
git apply -R /path/to/slash-worker-heartbeat-v0.22.6.patch
hermes gateway restart
```
