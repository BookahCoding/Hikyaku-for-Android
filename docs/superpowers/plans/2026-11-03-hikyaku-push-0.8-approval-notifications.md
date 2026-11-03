# hikyaku-push 0.8.0 (approval notifications) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a bot waits for the user to approve a command, push one encrypted notification to every phone allowed that bot's profile.

**Architecture:** A new `ApprovalNotifier` is registered as an observer on Hermes' `pre_approval_request` hook. The hook never blocks: it filters, redacts, de-duplicates and hands the sealed push to a daemon thread. Nothing can be answered from the notification; the app opens the chat. No outbox, because the hook can run in a process without the watcher's leader.

**Tech Stack:** Python 3.11+, httpx, cryptography, pytest (the existing `hikyaku-push` repo, v0.7.0).

**Spec:** `docs/superpowers/specs/2026-11-03-hikyaku-push-0.8-reply-design.md` in the Hikyaku-for-Android repo (the narrowed, approval-only version). Evidence: `2026-11-03-hikyaku-push-0.8-feasibility-spike.md`.

## Global Constraints

- Work in `D:/Projects/hikyaku-push` on `master` (remote `origin` is `BookahCoding/hikyaku-push`). Do not push, tag or release until Task 3 and the user's go-ahead.
- Wire format unchanged: the envelope and every existing message type stay byte-compatible. The new `approval` message uses the type already documented in the protocol, with fields `bot`, `label`, `session`, `command`, `description`, `request_id`. It has no `choices` and no `expires`.
- Plugin identity unchanged: command `/herald-push`, data dir `plugin-data/herald-push`, config key `plugins.entries.herald-push`.
- No change to the gateway, no app change, no Hikyaku release in this plan.
- Baseline before any change: `253 passed, 3 skipped`. The suite must pass after every task.
- Test command (needed on this machine for the temp dir): `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
- Limits: command at most 160 characters, description at most 240; dedupe window 60 s; post timeout 10 s (connect 5 s).
- End every commit message with a blank line and these two trailer lines:
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01T1LnxJgKG7J6HiizazTYPP`

## Review Focus

1. **The hook must never slow or break the agent.** A slow ntfy server or an exception in sealing/posting must not reach the caller; the handler returns before any network call (Task 1).
2. **Secrets in the command.** Redaction is applied; if the host's redaction is unavailable the command is omitted and only the description goes (Task 1).
3. **A profile a phone is not allowed must not be pushed**, and a bot with "Send notifications" off sends nothing; a phone with no list hears every profile, as for `bot_message` (Task 1).
4. **Duplicate hook calls** (several plugin managers in one process) produce one push; the same text after 60 s produces another (Task 1).
5. **Old Hermes**: a Hermes without `register_hook`, or without the hook name, must not stop the plugin loading (Task 2). Also: worst-case escaped text still fits one 4096-byte push (Task 1).

## File Structure

| File | Responsibility |
|---|---|
| `herald_push/approvals.py` (create) | `ApprovalNotifier` (the hook's logic) and `attach(ctx, store, root)` (registers it) |
| `herald_push/push.py` (modify) | `SHRINKABLE` gains `description` and `command`, so an over-long escaped approval is cut instead of refused |
| `herald_push/plugin.py` (modify) | Call `attach` in long-lived processes; version 0.8.0 |
| `herald_push/commands.py` (modify) | `FEATURES` gains `approval_push` |
| `plugin.yaml`, `README.md`, `CHANGELOG.md` (modify) | Version and docs |
| `tests/test_approvals.py` (create) | Notifier and `attach` tests |
| `tests/test_command_router.py` (modify) | Feature test |

---

### Task 1: The approval notifier

**Files:**
- Create: `herald_push/approvals.py`
- Modify: `herald_push/push.py` (the `SHRINKABLE` tuple)
- Test: `tests/test_approvals.py`

**Interfaces:**
- Consumes: `push.message(kind, **fields) -> dict`, `push.envelope_for(device, payload, sig_key) -> str`, `push.post(device, body, client=None) -> str`, `push.OK`, `Store.devices()`, `Store.allowlist().allows(device_id, profile) -> bool`, `Store.identity()` (third item is the signing key), `watcher.profile_enabled(home) -> bool`, `watcher.bot_label(home, profile) -> str`.
- Produces: constants `MAX_COMMAND = 160`, `MAX_DESCRIPTION = 240`, `DEDUPE_SECONDS = 60`; `ApprovalNotifier(store, profile, home, *, now=time.time, post=push.post, seal=push.envelope_for, redact=host_redact, enabled=profile_enabled, spawn=start_thread)` with `on_request(**kwargs) -> None` (the hook callback; never raises).

- [ ] **Step 1: Write the failing tests**

Create `tests/test_approvals.py`:
```python
import pytest

from herald_push import push
from herald_push.approvals import DEDUPE_SECONDS, MAX_COMMAND, MAX_DESCRIPTION, ApprovalNotifier
from herald_push.store import Store


class Sent:
    def __init__(self):
        self.posts, self.payloads = [], []

    def seal(self, device, payload, sig_key):
        self.payloads.append((device.device_id, payload))
        return "BODY-" + device.device_id[-2:]

    def post(self, device, body, client=None):
        self.posts.append((device.device_id, body))
        return push.OK


def build(tmp_path, clock, make_device, *, redact=lambda text: text.replace("SECRET", "***"), enabled=True,
          spawn=None, phones=1, profile="default", real_seal=False):
    store = Store(tmp_path / "data")
    devices = [make_device() for _ in range(phones)]
    for device in devices:
        store.upsert(device)
    sent = Sent()
    notifier = ApprovalNotifier(
        store, profile, tmp_path / "home", now=clock, post=sent.post,
        seal=push.envelope_for if real_seal else sent.seal, redact=redact,
        enabled=lambda home: enabled, spawn=spawn or (lambda fn: fn()))
    return store, devices, sent, notifier


def ask(notifier, **overrides):
    args = dict(command="rm -rf build", description="recursive delete", pattern_key="rm", session_key="s1",
                surface="gateway")
    args.update(overrides)
    notifier.on_request(**args)


def test_an_approval_request_reaches_the_phone(tmp_path, clock, make_device):
    _, (phone,), sent, notifier = build(tmp_path, clock, make_device)
    ask(notifier)
    assert [device_id for device_id, _ in sent.posts] == [phone.device_id]
    (_, payload), = sent.payloads
    assert payload["type"] == "approval" and payload["bot"] == "default" and payload["label"] == "default"
    assert payload["session"] == "s1" and payload["description"] == "recursive delete"
    assert payload["command"] == "rm -rf build"
    assert len(payload["request_id"]) == 12 and "choices" not in payload and "expires" not in payload


def test_only_phones_allowed_that_profile_are_told(tmp_path, clock, make_device):
    store, (allowed, other), sent, notifier = build(tmp_path, clock, make_device, phones=2)
    store.set_allowed_profiles(allowed.device_id, ["default"])
    store.set_allowed_profiles(other.device_id, ["somebody-else"])
    ask(notifier)
    assert [device_id for device_id, _ in sent.posts] == [allowed.device_id]


def test_a_phone_with_no_list_hears_every_profile(tmp_path, clock, make_device):
    _, (phone,), sent, notifier = build(tmp_path, clock, make_device, profile="side")
    ask(notifier)
    assert [device_id for device_id, _ in sent.posts] == [phone.device_id]
    assert sent.payloads[0][1]["bot"] == "side"


def test_the_secret_in_a_command_is_redacted(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device)
    ask(notifier, command="curl -H token=SECRET https://example.test")
    command = sent.payloads[0][1]["command"]
    assert "SECRET" not in command and "***" in command


def test_when_redaction_is_unavailable_only_the_description_goes(tmp_path, clock, make_device):
    def missing(text):
        raise ImportError("no agent.redact")

    _, _, sent, notifier = build(tmp_path, clock, make_device, redact=missing)
    ask(notifier, command="curl -H token=SECRET https://example.test")
    payload = sent.payloads[0][1]
    assert "command" not in payload and payload["description"] == "recursive delete"


def test_long_text_is_clipped(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device)
    ask(notifier, command="c" * 400, description="d" * 500)
    payload = sent.payloads[0][1]
    assert len(payload["command"]) <= MAX_COMMAND and len(payload["description"]) <= MAX_DESCRIPTION
    assert payload["command"].endswith("…") and payload["description"].endswith("…")


def test_a_bot_with_notifications_off_sends_nothing(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device, enabled=False)
    ask(notifier)
    assert sent.posts == []


def test_the_same_request_is_pushed_once_a_minute(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device)
    ask(notifier)
    ask(notifier)
    assert len(sent.posts) == 1
    ask(notifier, command="ls")  # a different request is its own push
    assert len(sent.posts) == 2
    clock.t += DEDUPE_SECONDS
    ask(notifier)
    assert len(sent.posts) == 3


def test_the_hook_returns_before_any_post_runs(tmp_path, clock, make_device):
    pending = []
    _, _, sent, notifier = build(tmp_path, clock, make_device, spawn=pending.append)
    ask(notifier)
    assert sent.posts == [] and len(pending) == 1
    pending[0]()
    assert len(sent.posts) == 1


def test_no_registered_phone_means_no_work(tmp_path, clock, make_device):
    pending = []
    _, _, sent, notifier = build(tmp_path, clock, make_device, phones=0, spawn=pending.append)
    ask(notifier)
    assert pending == [] and sent.posts == []


def test_a_failing_post_never_reaches_the_agent(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device)

    def boom(device, body, client=None):
        raise RuntimeError("ntfy is on fire")

    notifier.post = boom
    ask(notifier)  # must not raise


def test_a_failing_seal_never_reaches_the_agent(tmp_path, clock, make_device):
    _, _, _, notifier = build(tmp_path, clock, make_device)

    def too_big(device, payload, sig_key):
        raise ValueError("push message too large")

    notifier.seal = too_big
    ask(notifier)  # must not raise


def test_a_hook_call_with_odd_arguments_never_raises(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device)
    notifier.on_request()
    notifier.on_request(command=None, description=None, session_key=None, pattern_key=None)
    notifier.on_request(command=5, description=["x"], extra="ignored")


def test_the_worst_case_escaped_text_still_fits_one_push(tmp_path, clock, make_device):
    _, _, sent, notifier = build(tmp_path, clock, make_device, real_seal=True, redact=lambda text: text)
    ask(notifier, command='"\\' * 200, description='"\\' * 300)
    assert sent.posts, "the push was refused instead of shortened"
    assert all(len(body.encode("utf-8")) <= push.MAX_BODY for _, body in sent.posts)
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_approvals.py -q --basetemp=.pytest-tmp`
Expected: FAIL at collection (`No module named 'herald_push.approvals'`).

- [ ] **Step 3: Make the escaped fields shrinkable**

In `herald_push/push.py`, change
```python
SHRINKABLE = ("text", "details", "reason")
```
to
```python
SHRINKABLE = ("text", "details", "reason", "description", "command")
```

- [ ] **Step 4: Implement**

Create `herald_push/approvals.py`:
```python
"""Tells registered phones that a bot is waiting for the user to approve a command.

Hermes calls the ``pre_approval_request`` observer in the process where the approval happens (the dashboard for an
app chat, the messaging gateway for a Telegram one). The observer must never slow or break the agent: it only
filters, redacts and de-duplicates, then hands the sealed push to a daemon thread. Nothing can be answered from the
notification; the phone opens the chat, where the user approves as usual. There is no outbox: this may run in a
process that isn't the watcher's leader, and an approval is only useful for minutes.
"""

from __future__ import annotations

import hashlib
import logging
import re
import threading
import time
from pathlib import Path
from typing import Callable, Dict, Optional

import httpx

from . import push
from .store import PROFILE_RE, Store
from .watcher import bot_label, profile_enabled

log = logging.getLogger(__name__)

MAX_COMMAND = 160
MAX_DESCRIPTION = 240
DEDUPE_SECONDS = 60
HOOK = "pre_approval_request"
_TIMEOUT = httpx.Timeout(10.0, connect=5.0)


def host_redact(text: str) -> str:
    """Hermes' own redaction of secrets in a command. ImportError when this Hermes doesn't have it."""
    from agent.redact import redact_sensitive_text
    return redact_sensitive_text(text, force=True)


def start_thread(work: Callable[[], None]) -> None:
    threading.Thread(target=work, name="herald-push-approval", daemon=True).start()


def clip(text: str, limit: int) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


class ApprovalNotifier:
    def __init__(self, store: Store, profile: str, home: Path, *, now: Callable[[], float] = time.time,
                 post=push.post, seal=push.envelope_for, redact: Callable[[str], str] = host_redact,
                 enabled: Callable[[Path], bool] = profile_enabled,
                 spawn: Callable[[Callable[[], None]], None] = start_thread):
        self.store = store
        self.profile = profile
        self.home = home
        self.now = now
        self.post = post
        self.seal = seal
        self.redact = redact
        self.enabled = enabled
        self.spawn = spawn
        self._lock = threading.Lock()
        self._recent: Dict[str, float] = {}

    def on_request(self, **kwargs) -> None:
        """The hook callback. Never raises: an exception here would land in the agent's approval path."""
        try:
            self._notify(kwargs)
        except Exception:
            log.exception("herald-push: couldn't prepare an approval push")

    def _notify(self, kwargs: dict) -> None:
        if not self.enabled(self.home):
            return
        allow = self.store.allowlist()
        devices = [d for d in self.store.devices() if allow.allows(d.device_id, self.profile)]
        if not devices:
            return
        command = str(kwargs.get("command") or "")
        description = str(kwargs.get("description") or "")
        session = str(kwargs.get("session_key") or "")
        key = hashlib.sha256("\x1f".join((session, str(kwargs.get("pattern_key") or ""), command)).encode("utf-8")).hexdigest()
        if self._seen_recently(key):
            return
        payload = push.message(
            "approval", bot=self.profile, label=bot_label(self.home, self.profile), session=session or None,
            command=self._safe_command(command), description=clip(description, MAX_DESCRIPTION) or None,
            request_id=key[:12])
        sig = self.store.identity()[2]
        self.spawn(lambda: self._send(devices, payload, sig))

    def _safe_command(self, command: str) -> Optional[str]:
        """The command with secrets redacted and clipped, or None when it can't be redacted."""
        if not command:
            return None
        try:
            return clip(self.redact(command), MAX_COMMAND) or None
        except Exception:
            return None

    def _seen_recently(self, key: str) -> bool:
        now = self.now()
        with self._lock:
            self._recent = {k: t for k, t in self._recent.items() if now - t < DEDUPE_SECONDS}
            if key in self._recent:
                return True
            self._recent[key] = now
        return False

    def _send(self, devices, payload: dict, sig) -> None:
        try:
            with httpx.Client(timeout=_TIMEOUT, follow_redirects=False) as client:
                for device in devices:
                    try:
                        body = self.seal(device, payload, sig)
                        outcome = self.post(device, body, client)
                    except Exception as exc:
                        log.warning("herald-push: an approval push for %s failed: %s", self.profile, type(exc).__name__)
                        continue
                    if outcome != push.OK:
                        log.warning("herald-push: an approval push for %s was not taken (%s)", self.profile, outcome)
        except Exception:
            log.exception("herald-push: sending approval pushes failed")
```

- [ ] **Step 5: Run to verify they pass, then the whole suite**

Run: `.venv/Scripts/python -m pytest tests/test_approvals.py -q --basetemp=.pytest-tmp`
Expected: all pass. Then `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`: all pass (253 + the new ones).

- [ ] **Step 6: Commit**
```bash
git add herald_push/approvals.py herald_push/push.py tests/test_approvals.py
git commit -m "approvals: push a notification when a bot waits for approval"
```
(append the two trailer lines)

---

### Task 2: Register the hook, announce the feature, version 0.8.0

**Files:**
- Modify: `herald_push/approvals.py` (add `attach`), `herald_push/plugin.py`, `herald_push/commands.py`, `plugin.yaml`, `README.md`, `CHANGELOG.md`
- Test: `tests/test_approvals.py` (append), `tests/test_command_router.py` (append)

**Interfaces:**
- Consumes: `ApprovalNotifier`, `HOOK` (Task 1); the context's `register_hook(name, callback)` and `_manager.home_path`.
- Produces: `approvals.attach(ctx, store: Store, root: Path) -> Optional[ApprovalNotifier]` (None when this Hermes cannot register the hook or the profile name is not valid); `FEATURES == ["profile_allowlist", "reliable_push", "approval_push"]`; version `0.8.0` in `plugin.yaml` and `plugin.py`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_approvals.py`:
```python
import types
from pathlib import Path

from herald_push import plugin
from herald_push.approvals import HOOK, attach


class HookCtx:
    def __init__(self, home, *, error=None):
        self.hooks, self._error = [], error
        self._manager = types.SimpleNamespace(home_path=home)

    def register_hook(self, name, callback):
        if self._error is not None:
            raise self._error
        self.hooks.append((name, callback))


def test_attach_registers_the_observer_for_the_plugins_own_profile(tmp_path):
    root = tmp_path / "hermes"
    store = Store(tmp_path / "data")
    default = HookCtx(root)
    notifier = attach(default, store, root)
    assert [name for name, _ in default.hooks] == [HOOK] and notifier.profile == "default"
    side = HookCtx(root / "profiles" / "side")
    assert attach(side, store, root).profile == "side"
    assert default.hooks[0][1] == notifier.on_request


def test_attach_refuses_a_profile_name_it_cannot_use(tmp_path):
    root = tmp_path / "hermes"
    assert attach(HookCtx(root / "profiles" / "bad name!"), Store(tmp_path / "data"), root) is None


def test_attach_survives_a_hermes_without_the_hook(tmp_path):
    root = tmp_path / "hermes"
    store = Store(tmp_path / "data")
    assert attach(HookCtx(root, error=ValueError("unknown hook")), store, root) is None
    assert attach(types.SimpleNamespace(_manager=types.SimpleNamespace(home_path=root)), store, root) is None


def test_register_attaches_the_hook_only_in_a_long_lived_process(tmp_path, monkeypatch):
    class Ctx(HookCtx):
        def __init__(self):
            super().__init__(tmp_path)
            self.commands, self.unloads = [], []

        def register_command(self, name, handler, description="", args_hint=""):
            self.commands.append(name)

        def on_unload(self, callback):
            self.unloads.append(callback)

    class DummyWatcher:
        def __init__(self, *args, **kwargs):
            pass

        def start(self):
            pass

        def stop(self):
            pass

    monkeypatch.setattr(plugin, "_root", lambda: tmp_path)
    monkeypatch.setattr(plugin, "register_tools", lambda ctx, phones: None)
    monkeypatch.setattr(plugin, "Watcher", DummyWatcher)
    monkeypatch.setattr(plugin, "_watcher", None)

    monkeypatch.setattr(plugin, "is_long_lived_process", lambda: False)
    short = Ctx()
    plugin.register(short)
    assert short.commands == ["herald-push"] and short.hooks == []

    monkeypatch.setattr(plugin, "is_long_lived_process", lambda: True)
    long_lived = Ctx()
    plugin.register(long_lived)
    assert [name for name, _ in long_lived.hooks] == [HOOK]
    for stop in long_lived.unloads:
        stop()
```
Append to `tests/test_command_router.py`:
```python


def test_info_announces_approval_push(store):
    assert "approval_push" in answer(handler(store), "info")["features"]
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_approvals.py tests/test_command_router.py -q --basetemp=.pytest-tmp`
Expected: FAIL (`cannot import name 'attach'` and the missing feature).

- [ ] **Step 3: Implement**

Append to `herald_push/approvals.py`:
```python


def attach(ctx, store: Store, root: Path) -> Optional[ApprovalNotifier]:
    """Registers the approval observer for this plugin's profile. None when this Hermes can't (no hook support) or
    the profile's name is not one the plugin handles; the plugin then simply sends no approval pushes."""
    register = getattr(ctx, "register_hook", None)
    if register is None:
        return None
    manager = getattr(ctx, "_manager", None)
    home = Path(getattr(manager, "home_path", None) or root)
    profile = "default" if home.resolve() == Path(root).resolve() else home.name
    if not PROFILE_RE.match(profile):
        log.warning("herald-push: approval pushes are off for a profile with an unusable name")
        return None
    notifier = ApprovalNotifier(store, profile, home)
    try:
        register(HOOK, notifier.on_request)
    except Exception:
        log.warning("herald-push: this Hermes can't register %s; approval pushes are off", HOOK, exc_info=True)
        return None
    return notifier
```
In `herald_push/plugin.py`: change `from .commands import make_handler` block by adding `from .approvals import attach` (alphabetical, first import after `from . import push`), set `VERSION = "0.8.0"`, and in `register` replace
```python
    if not is_long_lived_process():
        return
    with _watcher_lock:
```
with
```python
    if not is_long_lived_process():
        return
    attach(ctx, store, root)
    with _watcher_lock:
```
In `herald_push/commands.py`: `FEATURES = ["profile_allowlist", "reliable_push"]` becomes `FEATURES = ["profile_allowlist", "reliable_push", "approval_push"]`.
In `plugin.yaml`: `version: 0.7.0` becomes `version: 0.8.0`.
In `CHANGELOG.md`, insert above `## 0.7.0`:
```markdown
## 0.8.0

- When a bot waits for the user to approve a command, every phone allowed that bot's profile gets one encrypted `approval` notification (bot, a short description and the command with secrets redacted). Tapping it opens the chat in Hikyaku; the approval itself is answered there, as before.
- It is sent from a background thread, so it can never slow the agent, and a failed send is logged and dropped. The same request seen twice within a minute is pushed once.
- If this Hermes can't redact the command, only the description is sent. A Hermes without the approval hook simply sends nothing.
- `info` and `register` answers list the `approval_push` feature. Old apps ignore the new message.

```
In `README.md`, add before `## Delivery (0.7.0)`:
```markdown
## Approval notifications (0.8.0)

When a bot is blocked waiting for you to approve a command, each phone allowed that bot's profile gets one end-to-end encrypted notification naming the bot, what it wants to do and the command (secrets redacted by Hermes, clipped to 160 characters). Tapping it opens the chat in Hikyaku, where you approve as usual: nothing can be approved from the notification, and the plugin never changes how approvals work. A failed notification is dropped, not retried, because an approval is only useful for minutes.

```

- [ ] **Step 4: Run to verify they pass, then the whole suite**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: all pass, including the existing test that fails if `plugin.yaml` and `plugin.py` versions differ.

- [ ] **Step 5: Commit**
```bash
git add -A
git commit -m "0.8.0: register the approval hook, announce approval_push"
```
(append the two trailer lines)

---

### Task 3: Publish and release (STOP: needs the user's go-ahead)

Tasks 1 and 2 touch only the local clone. Do not start this task until the user says to publish.

- [ ] **Step 1: Ask for the go-ahead.** Show `git log --oneline` since `v0.7.0` and the `CHANGELOG.md` entry.
- [ ] **Step 2: Push and wait for CI.**
```bash
cd "D:/Projects/hikyaku-push"
git push origin master
RID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId'); gh run watch "$RID" --exit-status --interval 20
```
Expected: all four matrix jobs green. If one fails, report the log; do not tag.
- [ ] **Step 3: Tag and release.**
```bash
git tag -a v0.8.0 -m "0.8.0: approval notifications"
git push origin v0.8.0
gh release create v0.8.0 --title "0.8.0: approval notifications" --notes "$(sed -n '/^## 0.8.0/,/^## 0.7.0/p' CHANGELOG.md | sed '$d')"
```
- [ ] **Step 4: Hand back, do not deploy.** The staged install on the gateway, and the Hikyaku 1.3.0 change that shows the notification (including pinning the new plugin commit), each need their own approval and plan.

---

## Self-Review

- **Spec coverage:** hook on `pre_approval_request` with a non-blocking daemon thread (Task 1); allowlist, per-profile switch, redaction with description-only fallback, clipping, dedupe, no `choices`/`expires` (Task 1); `approval_push` feature and version (Task 2); old-Hermes safety (Task 2 `attach` tests); docs (Task 2); release gate (Task 3). The phone side (Hikyaku 1.3.0) and the staged install are explicitly outside this plan.
- **Placeholders:** none.
- **Type consistency:** `ApprovalNotifier(store, profile, home, *, now, post, seal, redact, enabled, spawn)`, `on_request(**kwargs)`, `attach(ctx, store, root)`, `HOOK`, `MAX_COMMAND`, `MAX_DESCRIPTION`, `DEDUPE_SECONDS` are used with the same names throughout. The tests replace `notifier.post` and `notifier.seal` as attributes, which the class defines.
- **Known limits to state at release:** the hook's argument list has no approval timeout, so no countdown; `agent.redact` is a host module, not a documented plugin API; one push per process is possible if two managers see differently worded commands.
