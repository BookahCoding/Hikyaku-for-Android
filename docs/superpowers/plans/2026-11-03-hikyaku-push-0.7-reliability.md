# hikyaku-push 0.7.0 (reliability) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Herald push plugin deliver reliably: a slow or failing ntfy server must not stall other phones or lose pushes, and idle polling must cost less.

**Architecture:** The watcher stops posting to ntfy inline. It hands each sealed envelope to a `SendQueue`, which persists it in an on-disk `Outbox` and delivers it from one worker thread per phone, retrying with backoff. Polling skips a profile whose `state.db` signature is unchanged. The `/herald-push` command dispatch becomes a handler table with one shared JSON parser. The wire format and the plugin's internal identity (`herald-push`) do not change.

**Tech Stack:** Python 3.11+, httpx, cryptography, pyyaml, pytest.

**Spec:** `docs/superpowers/specs/2026-11-03-hikyaku-push-plugin-design.md` (section 3) in the Hikyaku-for-Android repo.

## Global Constraints

- Work in a **new local clone** at `D:/Projects/hikyaku-push`. Do **not** push to, or change, `BookahCoding/hermes-herald-push` or `Giton22/hermes-herald-push`. Task 0 removes the clone's `origin` remote to make that impossible.
- The GitHub repo `BookahCoding/hikyaku-push` is created only in Task 8, only after the user says go.
- Do not touch the Asus gateway or any live Hermes install. No Hikyaku (Android) changes in this plan.
- Wire format unchanged: the envelope, the plaintext message types, and the `/herald-push` command arguments and JSON answers stay byte-compatible. Existing tests pin them.
- Plugin identity unchanged: command `/herald-push`, data dir `plugin-data/herald-push`, config key `plugins.entries.herald-push`.
- Keep Giton22's history, authorship and the MIT license text intact.
- Baseline before any change: `192 passed, 2 skipped`. The full suite must pass after every task.
- On this machine pytest needs `--basetemp` (a stale temp dir owned by another account breaks cleanup). Test command used throughout: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
- Delivery rules (from the spec): per-phone ordering; retry backoff 5 s, 30 s, 5 min, then hourly; items older than 24 h dropped; at most 200 queued per phone; the outbox stores the sealed envelope only (never plaintext); connect timeout 5 s and total timeout 10 s for queued sends.
- End every commit message with these two trailer lines (blank line before them):
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01T1LnxJgKG7J6HiizazTYPP`

## Review Focus

Failure modes the spec implies but a happy-path test would miss, most likely first. Each has a test in the task named.

1. **ntfy is down for hours, then returns.** Queued pushes must arrive oldest first, none older than 24 h, none lost (Task 2 expiry/order tests, Task 3 retry test).
2. **Plugin killed after posting but before the item is removed.** The item must still be on disk and be re-posted with the same body, so the phone's duplicate filter absorbs it (Task 2 persistence test, Task 3 resume test).
3. **A phone is removed or re-registers (new topic) while items are queued.** Nothing may be posted to the old topic (Task 3).
4. **`outbox.json` is corrupt, or the disk is full.** The plugin must still start and keep serving; a full disk makes `submit` return False rather than raise (Task 2, Task 3).
5. **One phone's server hangs.** Other phones' pushes still go out (Task 3 threaded test). Also: a poll that raised must not leave a profile marked "unchanged" (Task 5).

## File Structure

| File | Responsibility |
|---|---|
| `herald_push/push.py` (modify) | `post()` classifies an ntfy answer as `OK` / `RETRY` / `DROP`; `publish()` keeps its signature |
| `herald_push/outbox.py` (create) | On-disk queue of sealed envelopes: order, backoff, expiry, cap |
| `herald_push/sender.py` (create) | `SendQueue`: seals + queues, one worker thread per phone |
| `herald_push/watcher.py` (modify) | Delivers via a sender when one is set; skips unchanged databases |
| `herald_push/commands.py` (modify) | Handler table and `op_and_json` helper; `reliable_push` feature |
| `herald_push/plugin.py`, `plugin.yaml` (modify) | Version 0.7.0; enable the signature check in the real watcher |
| `tests/conftest.py` (create) | `clock`, `make_device`, `wait_until` fixtures |
| `tests/test_push_post.py`, `test_outbox.py`, `test_sender.py`, `test_watcher_queue.py`, `test_watcher_skip.py`, `test_command_router.py` (create) | One file per unit |
| `README.md`, `CHANGELOG.md`, `.github/workflows/test.yml`, `requirements-dev.txt` (create/modify) | Docs and CI |

---

### Task 0: Local clone and baseline

**Files:**
- Create: `D:/Projects/hikyaku-push/` (clone), `.venv`, `.gitignore` additions, `requirements-dev.txt`

**Interfaces:**
- Produces: a working local repo with a passing baseline, no remote.

- [ ] **Step 1: Clone and cut the remote**

```bash
git clone https://github.com/BookahCoding/hermes-herald-push.git "D:/Projects/hikyaku-push"
cd "D:/Projects/hikyaku-push"
git remote remove origin
git remote -v
```
Expected: `git remote -v` prints nothing.

- [ ] **Step 2: Create the venv and dev requirements**

Create `requirements-dev.txt`:
```
pytest>=8
httpx>=0.27
cryptography>=42
pyyaml>=6
```
Append to `.gitignore`:
```
.venv/
.pytest-tmp/
__pycache__/
```
```bash
python -m venv .venv
.venv/Scripts/python -m pip install -q -r requirements-dev.txt
```

- [ ] **Step 3: Run the baseline**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: `192 passed, 2 skipped`. If the numbers differ, stop and report; do not continue on an unknown baseline.

- [ ] **Step 4: Add shared test fixtures**

Create `tests/conftest.py`:
```python
import time

import pytest

from herald_push import crypto
from herald_push.store import Device


class Clock:
    """A settable time source: call it for the time, change ``t`` to move it."""

    def __init__(self, t: float = 1_800_000_000.0):
        self.t = t

    def __call__(self) -> float:
        return self.t


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def make_device():
    count = {"n": 0}

    def make(device_id=None, server="https://ntfy.sh") -> Device:
        count["n"] += 1
        return Device.parse({
            "device_id": device_id or "00000000-0000-4000-8000-%012d" % count["n"],
            "name": "Phone %d" % count["n"], "server": server,
            "push_topic": crypto.new_topic(), "reply_topic": crypto.new_topic(),
            "enc_pub": crypto.encode_public(crypto.generate_key().public_key()),
            "sig_pub": crypto.encode_public(crypto.generate_key().public_key()),
        })

    return make


@pytest.fixture
def wait_until():
    def wait(condition, timeout: float = 3.0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if condition():
                return True
            time.sleep(0.01)
        return bool(condition())

    return wait
```

- [ ] **Step 5: Re-run and commit**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: still `192 passed, 2 skipped`.
```bash
git add .gitignore requirements-dev.txt tests/conftest.py
git commit -m "Dev setup: requirements, test fixtures"
```
(append the two trailer lines)

---

### Task 1: Classify what ntfy answers

**Files:**
- Modify: `herald_push/push.py` (replace `publish`, add `post`, constants)
- Test: `tests/test_push_post.py`

**Interfaces:**
- Consumes: `push.envelope_for(device, payload, sig_key) -> str` (existing).
- Produces: `push.OK`, `push.RETRY`, `push.DROP` (strings); `push.post(device: Device, body: str, client: Optional[httpx.Client] = None) -> str`; `push.publish(device, payload, sig_key, client=None) -> bool` (unchanged signature and meaning).

- [ ] **Step 1: Write the failing tests**

Create `tests/test_push_post.py`:
```python
import httpx
import pytest

from herald_push import crypto, push


def client_answering(status=None, raises=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if raises is not None:
            raise raises
        return httpx.Response(status)

    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.mark.parametrize("status", [200, 202, 299])
def test_a_2xx_answer_is_delivery(make_device, status):
    assert push.post(make_device(), "body", client_answering(status)) == push.OK


@pytest.mark.parametrize("status", [408, 429, 500, 502, 503])
def test_busy_or_broken_servers_are_worth_retrying(make_device, status):
    assert push.post(make_device(), "body", client_answering(status)) == push.RETRY


@pytest.mark.parametrize("status", [301, 400, 403, 404, 413])
def test_answers_that_retrying_cannot_fix_are_dropped(make_device, status):
    assert push.post(make_device(), "body", client_answering(status)) == push.DROP


def test_no_connection_is_worth_retrying(make_device):
    client = client_answering(raises=httpx.ConnectError("down"))
    assert push.post(make_device(), "body", client) == push.RETRY


def test_post_sends_the_body_to_the_phones_topic(make_device):
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((str(request.url), request.content, request.headers.get("content-type")))
        return httpx.Response(200)

    device = make_device()
    push.post(device, "sealed-body", httpx.Client(transport=httpx.MockTransport(handler)))
    assert seen == [(f"{device.server}/{device.push_topic}", b"sealed-body", "text/plain")]


def test_publish_keeps_its_meaning(make_device):
    device, sig = make_device(), crypto.generate_key()
    assert push.publish(device, push.message("ping"), sig, client_answering(200)) is True
    assert push.publish(device, push.message("ping"), sig, client_answering(500)) is False
    too_big = {"id": "x", "ts": 1, "type": "x", "blob": "y" * 10000}
    assert push.publish(device, too_big, sig, client_answering(200)) is False
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_push_post.py -q --basetemp=.pytest-tmp`
Expected: FAIL (`module 'herald_push.push' has no attribute 'post'` / `OK`).

- [ ] **Step 3: Implement**

In `herald_push/push.py`, replace everything from the line `def publish(` to the end of the file with:
```python
OK, RETRY, DROP = "ok", "retry", "drop"
_HEADERS = {"Content-Type": "text/plain"}


def post(device: Device, body: str, client: Optional[httpx.Client] = None) -> str:
    """Posts a sealed envelope to the phone's topic. ``OK`` when ntfy took it; ``RETRY`` when trying again later
    may work (no connection, 408, 429, 5xx); ``DROP`` for an answer that retrying won't change. No title, tags or
    priority headers: they'd travel in the clear."""
    url = f"{device.server}/{device.push_topic}"
    content = body.encode("utf-8")
    try:
        if client is None:
            with httpx.Client(timeout=_TIMEOUT, follow_redirects=False) as own:
                response = own.post(url, content=content, headers=_HEADERS)
        else:
            response = client.post(url, content=content, headers=_HEADERS)
    except httpx.HTTPError as exc:
        log.warning("herald-push: couldn't reach %s: %s", device.server, type(exc).__name__)
        return RETRY
    code = response.status_code
    if code < 300:
        return OK
    log.warning("herald-push: %s answered %s", device.server, code)
    return RETRY if code in (408, 429) or code >= 500 else DROP


def publish(device: Device, payload: dict, sig_key, client: Optional[httpx.Client] = None) -> bool:
    """True when ntfy took it, right now. Used where the caller wants the answer (the ``test`` command)."""
    try:
        body = envelope_for(device, payload, sig_key)
    except ValueError:
        log.warning("herald-push: a %s message is too large to send", payload.get("type"))
        return False
    return post(device, body, client) == OK
```

- [ ] **Step 4: Run to verify they pass, then the whole suite**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: all pass (baseline plus the new tests).

- [ ] **Step 5: Commit**
```bash
git add herald_push/push.py tests/test_push_post.py
git commit -m "push: classify ntfy answers as ok, retry or drop"
```
(append the two trailer lines)

---

### Task 2: The outbox

**Files:**
- Create: `herald_push/outbox.py`
- Test: `tests/test_outbox.py`

**Interfaces:**
- Consumes: `store._write_private(path: Path, data: object) -> None` (atomic, mode 0600).
- Produces:
  - `Item` dataclass: `id: str, device_id: str, topic: str, body: str, queued_at: float, attempts: int = 0, next_try: float = 0.0`.
  - `Outbox(path, now=time.time, max_age=MAX_AGE_SECONDS, max_per_device=MAX_PER_DEVICE)` with `add(device_id, topic, body) -> Item`, `head(device_id) -> Optional[Item]` (a copy; oldest, expired ones pruned first), `done(item_id) -> None`, `retry_later(item_id) -> None`, `device_ids() -> List[str]`, `drop_device(device_id) -> None`.
  - Constants `BACKOFF_SECONDS = (5, 30, 300, 3600)`, `MAX_AGE_SECONDS = 86400`, `MAX_PER_DEVICE = 200`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_outbox.py`:
```python
import os
import stat
import sys

import pytest

from herald_push.outbox import BACKOFF_SECONDS, Outbox


def test_items_survive_a_restart(tmp_path, clock):
    path = tmp_path / "outbox.json"
    item = Outbox(path, now=clock).add("dev-1", "hp-a", "BODY")
    head = Outbox(path, now=clock).head("dev-1")
    assert (head.id, head.topic, head.body, head.attempts) == (item.id, "hp-a", "BODY", 0)


def test_oldest_first_for_each_device(tmp_path, clock):
    box = Outbox(tmp_path / "outbox.json", now=clock)
    box.add("a", "t", "1")
    box.add("b", "t", "x")
    box.add("a", "t", "2")
    assert box.device_ids() == ["a", "b"]
    box.done(box.head("a").id)
    assert box.head("a").body == "2" and box.head("b").body == "x"


def test_backoff_steps_then_stays_hourly(tmp_path, clock):
    box = Outbox(tmp_path / "outbox.json", now=clock)
    item = box.add("a", "t", "1")
    delays = []
    for _ in range(6):
        box.retry_later(item.id)
        delays.append(box.head("a").next_try - clock.t)
    assert delays == [5, 30, 300, 3600, 3600, 3600]
    assert tuple(delays[:4]) == BACKOFF_SECONDS
    assert box.head("a").attempts == 6


def test_an_item_expires_after_a_day_and_is_gone_from_disk(tmp_path, clock):
    path = tmp_path / "outbox.json"
    box = Outbox(path, now=clock)
    box.add("a", "t", "old")
    clock.t += 24 * 3600 - 1
    assert box.head("a").body == "old"
    clock.t += 2
    assert box.head("a") is None
    assert Outbox(path, now=clock).head("a") is None


def test_the_cap_drops_the_oldest(tmp_path, clock):
    box = Outbox(tmp_path / "outbox.json", now=clock, max_per_device=3)
    for n in range(5):
        box.add("a", "t", str(n))
    box.add("b", "t", "other")
    bodies = []
    while True:
        head = box.head("a")
        if head is None:
            break
        bodies.append(head.body)
        box.done(head.id)
    assert bodies == ["2", "3", "4"]
    assert box.head("b").body == "other"


def test_an_unreadable_file_starts_empty_and_works(tmp_path, clock):
    path = tmp_path / "outbox.json"
    path.write_text("{not json", encoding="utf-8")
    box = Outbox(path, now=clock)
    assert box.head("a") is None
    box.add("a", "t", "fresh")
    assert Outbox(path, now=clock).head("a").body == "fresh"


def test_a_file_with_the_wrong_shape_starts_empty(tmp_path, clock):
    path = tmp_path / "outbox.json"
    path.write_text('{"items": [{"unexpected": 1}]}', encoding="utf-8")
    assert Outbox(path, now=clock).head("a") is None


def test_drop_device_forgets_only_that_device(tmp_path, clock):
    box = Outbox(tmp_path / "outbox.json", now=clock)
    box.add("a", "t", "1")
    box.add("b", "t", "2")
    box.drop_device("a")
    assert box.head("a") is None and box.head("b").body == "2"


def test_a_copy_is_returned_so_callers_cant_edit_the_queue(tmp_path, clock):
    box = Outbox(tmp_path / "outbox.json", now=clock)
    box.add("a", "t", "1")
    box.head("a").attempts = 99
    assert box.head("a").attempts == 0


@pytest.mark.skipif(sys.platform == "win32", reason="POSIX modes")
def test_the_file_is_private(tmp_path, clock):
    path = tmp_path / "outbox.json"
    Outbox(path, now=clock).add("a", "t", "1")
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_outbox.py -q --basetemp=.pytest-tmp`
Expected: FAIL (`No module named 'herald_push.outbox'`).

- [ ] **Step 3: Implement**

Create `herald_push/outbox.py`:
```python
"""Pushes still to be delivered, kept on disk so a restart or a failing ntfy server doesn't lose them.

Each item is a sealed envelope: the message text is never written here in the clear. Items for one phone are
delivered oldest first. A failed attempt waits (5 s, 30 s, 5 min, then an hour); an item older than a day is
dropped, since the phone ignores anything older anyway; and a phone keeps at most MAX_PER_DEVICE items, oldest
dropped first.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import uuid
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Callable, List, Optional

from .store import _write_private

log = logging.getLogger(__name__)

BACKOFF_SECONDS = (5, 30, 300, 3600)
MAX_AGE_SECONDS = 24 * 3600
MAX_PER_DEVICE = 200


@dataclass
class Item:
    id: str
    device_id: str
    topic: str
    body: str
    queued_at: float
    attempts: int = 0
    next_try: float = 0.0


class Outbox:
    def __init__(self, path, now: Callable[[], float] = time.time, max_age: float = MAX_AGE_SECONDS,
                 max_per_device: int = MAX_PER_DEVICE):
        self.path = Path(path)
        self.now = now
        self.max_age = max_age
        self.max_per_device = max_per_device
        self._lock = threading.Lock()
        self._items: List[Item] = self._load()

    def _load(self) -> List[Item]:
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            return [Item(**row) for row in raw["items"]]
        except FileNotFoundError:
            return []
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            log.warning("herald-push: the outbox can't be read; starting with an empty one")
            return []

    def _save(self) -> None:
        _write_private(self.path, {"items": [asdict(item) for item in self._items]})

    def _expire(self) -> bool:
        cutoff = self.now() - self.max_age
        kept = [item for item in self._items if item.queued_at >= cutoff]
        changed = len(kept) != len(self._items)
        self._items = kept
        return changed

    def add(self, device_id: str, topic: str, body: str) -> Item:
        with self._lock:
            item = Item(uuid.uuid4().hex, device_id, topic, body, self.now())
            self._items.append(item)
            mine = [i for i in self._items if i.device_id == device_id]
            excess = len(mine) - self.max_per_device
            if excess > 0:
                dropped = {i.id for i in mine[:excess]}
                self._items = [i for i in self._items if i.id not in dropped]
            self._expire()
            self._save()
            return replace(item)

    def head(self, device_id: str) -> Optional[Item]:
        """The oldest item for a phone, or None. A copy: change the queue only through this class."""
        with self._lock:
            if self._expire():
                self._save()
            for item in self._items:
                if item.device_id == device_id:
                    return replace(item)
            return None

    def done(self, item_id: str) -> None:
        with self._lock:
            self._items = [i for i in self._items if i.id != item_id]
            self._save()

    def retry_later(self, item_id: str) -> None:
        with self._lock:
            for item in self._items:
                if item.id == item_id:
                    item.attempts += 1
                    item.next_try = self.now() + BACKOFF_SECONDS[min(item.attempts, len(BACKOFF_SECONDS)) - 1]
            self._save()

    def device_ids(self) -> List[str]:
        with self._lock:
            seen: List[str] = []
            for item in self._items:
                if item.device_id not in seen:
                    seen.append(item.device_id)
            return seen

    def drop_device(self, device_id: str) -> None:
        with self._lock:
            self._items = [i for i in self._items if i.device_id != device_id]
            self._save()
```

- [ ] **Step 4: Run to verify they pass**

Run: `.venv/Scripts/python -m pytest tests/test_outbox.py -q --basetemp=.pytest-tmp`
Expected: all pass (the POSIX-mode test is skipped on Windows).

- [ ] **Step 5: Whole suite and commit**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp` (all pass)
```bash
git add herald_push/outbox.py tests/test_outbox.py
git commit -m "outbox: a persistent queue of sealed pushes with backoff, expiry and a cap"
```
(append the two trailer lines)

---

### Task 3: The send queue

**Files:**
- Create: `herald_push/sender.py`
- Test: `tests/test_sender.py`

**Interfaces:**
- Consumes: `push.envelope_for`, `push.post`, `push.OK/RETRY/DROP`, `Outbox` (Task 2), `Store.get(device_id) -> Optional[Device]`.
- Produces: `SendQueue(store, outbox, post=push.post, client=None, now=time.time)` with `submit(device: Device, payload: dict, sig_key) -> bool` (True = queued), `start() -> None`, `stop() -> None`, `step(device_id: str) -> Optional[float]` (None: nothing queued; 0.0: handled one item, look again; otherwise seconds until the head is due). `submit` only wakes workers after `start()`; before that it just persists (this keeps tests deterministic).

- [ ] **Step 1: Write the failing tests**

Create `tests/test_sender.py`:
```python
import threading

import pytest

from herald_push import crypto, push
from herald_push.outbox import Outbox
from herald_push.sender import SendQueue
from herald_push.store import Store

SIG = crypto.generate_key()


def build(tmp_path, clock, make_device, post, phones=1):
    store = Store(tmp_path / "data")
    devices = [make_device() for _ in range(phones)]
    for device in devices:
        store.upsert(device)
    box = Outbox(tmp_path / "data" / "outbox.json", now=clock)
    return store, box, SendQueue(store, box, post=post, now=clock), devices


def test_a_delivered_push_leaves_the_outbox(tmp_path, clock, make_device):
    calls = []

    def post(device, body, client):
        calls.append((device.device_id, body))
        return push.OK

    _, box, queue, (phone,) = build(tmp_path, clock, make_device, post)
    assert queue.submit(phone, push.message("ping"), SIG) is True
    assert queue.step(phone.device_id) == 0.0
    assert len(calls) == 1 and calls[0][0] == phone.device_id
    assert box.head(phone.device_id) is None
    assert queue.step(phone.device_id) is None


def test_a_failed_push_waits_then_is_retried_with_the_same_body(tmp_path, clock, make_device):
    answers, bodies = [push.RETRY, push.OK], []

    def post(device, body, client):
        bodies.append(body)
        return answers.pop(0)

    _, box, queue, (phone,) = build(tmp_path, clock, make_device, post)
    queue.submit(phone, push.message("ping"), SIG)
    assert queue.step(phone.device_id) == 0.0
    assert box.head(phone.device_id).attempts == 1
    assert queue.step(phone.device_id) == pytest.approx(5.0)
    assert len(bodies) == 1  # not due yet: nothing posted
    clock.t += 5
    assert queue.step(phone.device_id) == 0.0
    assert bodies[0] == bodies[1]
    assert box.head(phone.device_id) is None


def test_an_answer_retrying_cant_fix_is_dropped_not_retried(tmp_path, clock, make_device):
    _, box, queue, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: push.DROP)
    queue.submit(phone, push.message("ping"), SIG)
    queue.step(phone.device_id)
    assert box.head(phone.device_id) is None


def test_a_post_that_raises_counts_as_a_retry(tmp_path, clock, make_device):
    def post(device, body, client):
        raise RuntimeError("boom")

    _, box, queue, (phone,) = build(tmp_path, clock, make_device, post)
    queue.submit(phone, push.message("ping"), SIG)
    assert queue.step(phone.device_id) == 0.0
    assert box.head(phone.device_id).attempts == 1


def test_a_phone_that_re_registered_gets_nothing_on_its_old_topic(tmp_path, clock, make_device):
    calls = []
    store, box, queue, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: calls.append(b) or push.OK)
    queue.submit(phone, push.message("ping"), SIG)
    store.upsert(make_device(device_id=phone.device_id))  # same phone, new topics
    assert queue.step(phone.device_id) == 0.0
    assert calls == [] and box.head(phone.device_id) is None


def test_a_removed_phone_gets_nothing(tmp_path, clock, make_device):
    calls = []
    store, box, queue, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: calls.append(b) or push.OK)
    queue.submit(phone, push.message("ping"), SIG)
    store.remove(phone.device_id)
    queue.step(phone.device_id)
    assert calls == [] and box.head(phone.device_id) is None


def test_a_message_too_big_to_seal_is_refused_and_not_queued(tmp_path, clock, make_device):
    _, box, queue, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: push.OK)
    too_big = {"id": "x", "ts": 1, "type": "x", "blob": "y" * 10000}
    assert queue.submit(phone, too_big, SIG) is False
    assert box.head(phone.device_id) is None


def test_a_full_disk_refuses_instead_of_raising(tmp_path, clock, make_device, monkeypatch):
    _, box, queue, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: push.OK)

    def full(*args, **kwargs):
        raise OSError("no space left on device")

    monkeypatch.setattr(box, "add", full)
    assert queue.submit(phone, push.message("ping"), SIG) is False


def test_one_hung_server_does_not_hold_up_another_phone(tmp_path, clock, make_device, wait_until):
    release, delivered = threading.Event(), []
    store, box, queue, (stuck, fine) = build(tmp_path, clock, make_device, None, phones=2)

    def post(device, body, client):
        if device.device_id == stuck.device_id:
            release.wait(5)
            return push.OK
        delivered.append(device.device_id)
        return push.OK

    queue.post = post
    queue.start()
    try:
        queue.submit(stuck, push.message("ping"), SIG)
        queue.submit(fine, push.message("ping"), SIG)
        assert wait_until(lambda: delivered == [fine.device_id])
        assert box.head(stuck.device_id) is not None  # still waiting on its own server
    finally:
        release.set()
        assert wait_until(lambda: box.head(stuck.device_id) is None)
        queue.stop()


def test_start_resumes_what_a_restart_left_behind(tmp_path, clock, make_device, wait_until):
    store, box, first, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: push.OK)
    first.submit(phone, push.message("ping"), SIG)  # never delivered: the process "died" here
    calls = []
    reloaded = Outbox(tmp_path / "data" / "outbox.json", now=clock)
    second = SendQueue(store, reloaded, post=lambda d, b, c: calls.append(b) or push.OK, now=clock)
    second.start()
    try:
        assert wait_until(lambda: len(calls) == 1)
        assert wait_until(lambda: reloaded.head(phone.device_id) is None)
    finally:
        second.stop()


def test_stop_ends_the_workers(tmp_path, clock, make_device, wait_until):
    _, box, queue, (phone,) = build(tmp_path, clock, make_device, lambda d, b, c: push.OK)
    queue.start()
    queue.submit(phone, push.message("ping"), SIG)
    assert wait_until(lambda: box.head(phone.device_id) is None)
    threads = list(queue._threads.values())
    queue.stop()
    assert all(not t.is_alive() for t in threads)
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_sender.py -q --basetemp=.pytest-tmp`
Expected: FAIL (`No module named 'herald_push.sender'`).

- [ ] **Step 3: Implement**

Create `herald_push/sender.py`:
```python
"""Delivers pushes without letting one phone's server hold up another's.

``submit`` seals a message, writes it to the outbox and wakes that phone's worker. The worker posts the oldest
item, removes it once ntfy took it (or once retrying can't help) and otherwise tries again later, as the outbox
schedules. A phone that was removed or re-registered since an item was queued gets nothing: the sealed envelope
is bound to the topic it was queued for.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Callable, Dict, Optional

import httpx

from . import push
from .outbox import Outbox
from .store import Device, Store

log = logging.getLogger(__name__)

# The longest a worker sleeps without looking again: a missed wake-up or a clock jump costs at most this.
MAX_SLEEP = 60.0
# After an unexpected error (a full disk, say) a worker waits this long before looking again.
ERROR_SLEEP = 5.0


class SendQueue:
    def __init__(self, store: Store, outbox: Outbox,
                 post: Callable[[Device, str, Optional[httpx.Client]], str] = push.post,
                 client: Optional[httpx.Client] = None, now: Callable[[], float] = time.time):
        self.store = store
        self.outbox = outbox
        self.post = post
        self.client = client
        self.now = now
        self._lock = threading.Lock()
        self._running = False
        self._stopping = threading.Event()
        self._wakes: Dict[str, threading.Event] = {}
        self._threads: Dict[str, threading.Thread] = {}

    def submit(self, device: Device, payload: dict, sig_key) -> bool:
        """Seals [payload] for [device] and queues it. False when it can't be queued (too large, or the disk
        refuses); the caller treats that as not sent."""
        try:
            body = push.envelope_for(device, payload, sig_key)
        except ValueError:
            log.warning("herald-push: a %s message is too large to send", payload.get("type"))
            return False
        try:
            self.outbox.add(device.device_id, device.push_topic, body)
        except OSError:
            log.exception("herald-push: couldn't queue a %s message", payload.get("type"))
            return False
        self._wake(device.device_id)
        return True

    def start(self) -> None:
        """Begins delivering, including whatever an earlier run left in the outbox."""
        with self._lock:
            self._running = True
        for device_id in self.outbox.device_ids():
            self._wake(device_id)

    def stop(self) -> None:
        with self._lock:
            self._running = False
            threads = list(self._threads.values())
            events = list(self._wakes.values())
        self._stopping.set()
        for event in events:
            event.set()
        for thread in threads:
            thread.join(timeout=5)

    def step(self, device_id: str) -> Optional[float]:
        """Handles the oldest item for a phone if it is due. None: nothing is queued. 0.0: one item was handled,
        look again. Otherwise the seconds until the oldest item is due."""
        item = self.outbox.head(device_id)
        if item is None:
            return None
        wait = item.next_try - self.now()
        if wait > 0:
            return wait
        device = self.store.get(device_id)
        if device is None or device.push_topic != item.topic:
            self.outbox.done(item.id)  # the phone is gone or has new topics: this envelope can't reach it
            return 0.0
        try:
            outcome = self.post(device, item.body, self.client)
        except Exception:
            log.exception("herald-push: sending a push failed")
            outcome = push.RETRY
        if outcome == push.RETRY:
            self.outbox.retry_later(item.id)
        else:
            self.outbox.done(item.id)
        return 0.0

    def _wake(self, device_id: str) -> None:
        with self._lock:
            if not self._running:
                return
            event = self._wakes.get(device_id)
            if event is None:
                event = self._wakes[device_id] = threading.Event()
                thread = threading.Thread(target=self._work, args=(device_id, event),
                                          name=f"herald-push-send-{device_id[:8]}", daemon=True)
                self._threads[device_id] = thread
                thread.start()
        event.set()

    def _work(self, device_id: str, wake: threading.Event) -> None:
        while not self._stopping.is_set():
            try:
                wait = self.step(device_id)
            except Exception:
                log.exception("herald-push: the send queue hit an error")
                wait = ERROR_SLEEP
            if wait == 0.0:
                continue
            wake.wait(MAX_SLEEP if wait is None else min(wait, MAX_SLEEP))
            wake.clear()
```

- [ ] **Step 4: Run to verify they pass**

Run: `.venv/Scripts/python -m pytest tests/test_sender.py -q --basetemp=.pytest-tmp`
Expected: all pass. If the threaded tests are flaky, report the failure; do not add sleeps to hide it.

- [ ] **Step 5: Whole suite and commit**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp` (all pass)
```bash
git add herald_push/sender.py tests/test_sender.py
git commit -m "sender: one worker per phone, so a slow ntfy server stalls only its own phones"
```
(append the two trailer lines)

---

### Task 4: The watcher delivers through the queue

**Files:**
- Modify: `herald_push/watcher.py` (imports; `Watcher.__init__`, `_run`, `stop`, `poll`, `poll_cron`; new `_deliver`, `_make_sender`)
- Test: `tests/test_watcher_queue.py`

**Interfaces:**
- Consumes: `SendQueue.submit/start/stop` (Task 3), `Outbox(path, now)`.
- Produces: `Watcher(root, store, enabled=profile_enabled, now=time.time, sender=None, signature=None)`; attribute `sender`; method `_deliver(device, payload, sig, client) -> bool`. With `sender=None` (every existing test) `poll` still posts synchronously through `push.publish`, so existing tests are unchanged. `signature` is used in Task 5.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_watcher_queue.py`:
```python
import sqlite3

import pytest

from herald_push import push, watcher
from herald_push.sender import SendQueue
from herald_push.store import Store


def _make_db(home, rows):
    home.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(home / "state.db")
    conn.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, content TEXT,"
                 " tool_calls TEXT, active INTEGER DEFAULT 1, display_kind TEXT, timestamp REAL)")
    conn.executemany("INSERT INTO messages (session_id, role, content, tool_calls, active, display_kind, timestamp) "
                     "VALUES (?,?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()


def _add(home, *rows):
    conn = sqlite3.connect(home / "state.db")
    conn.executemany("INSERT INTO messages (session_id, role, content, tool_calls, active, display_kind, timestamp) "
                     "VALUES (?,?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()


class FakeSender:
    def __init__(self, accept=True):
        self.items, self.accept = [], accept

    def submit(self, device, payload, sig_key):
        self.items.append((device.device_id, payload))
        return self.accept


def _watcher(tmp_path, monkeypatch, make_device, sender):
    now = 1_800_000_000.0
    root = tmp_path / "hermes"
    _make_db(root, [])
    monkeypatch.setattr(watcher, "bot_chat_tip", lambda home: "s1" if (home / "state.db").exists() else None)
    monkeypatch.setattr(push, "publish", lambda *a, **k: pytest.fail("posted straight to ntfy"))
    store = Store(tmp_path / "data")
    device = make_device()
    store.upsert(device)
    return root, device, watcher.Watcher(root, store, now=lambda: now, sender=sender), now


def test_replies_go_to_the_sender_not_straight_to_ntfy(tmp_path, monkeypatch, make_device):
    sender = FakeSender()
    root, device, w, now = _watcher(tmp_path, monkeypatch, make_device, sender)
    w.poll()
    _add(root, ("s1", "assistant", "Hello", None, 1, None, now))
    assert w.poll() == 1
    (device_id, payload), = sender.items
    assert device_id == device.device_id
    assert payload["type"] == "bot_message" and payload["text"] == "Hello" and payload["bot"] == "default"


def test_a_refused_push_is_not_counted(tmp_path, monkeypatch, make_device):
    root, _, w, now = _watcher(tmp_path, monkeypatch, make_device, FakeSender(accept=False))
    w.poll()
    _add(root, ("s1", "assistant", "Hello", None, 1, None, now))
    assert w.poll() == 0


def test_without_a_sender_the_watcher_still_posts_directly(tmp_path, monkeypatch, make_device):
    root, _, w, now = _watcher(tmp_path, monkeypatch, make_device, None)
    sent = []
    monkeypatch.setattr(push, "publish", lambda device, payload, sig, client=None: sent.append(payload) or True)
    w.poll()
    _add(root, ("s1", "assistant", "Hello", None, 1, None, now))
    assert w.poll() == 1 and sent[0]["text"] == "Hello"


def test_the_leader_sends_through_a_queue_and_stops_it(tmp_path, wait_until):
    root = tmp_path / "hermes"
    root.mkdir()
    w = watcher.Watcher(root, Store(tmp_path / "data"))
    w.start()
    try:
        assert wait_until(lambda: w.sender is not None)
        assert isinstance(w.sender, SendQueue)
        queue = w.sender
    finally:
        w.stop()
    assert queue._stopping.is_set()
    assert w.sender is None
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_watcher_queue.py -q --basetemp=.pytest-tmp`
Expected: FAIL (`Watcher.__init__() got an unexpected keyword argument 'sender'`).

- [ ] **Step 3: Implement**

In `herald_push/watcher.py`:

(a) Imports. Replace
```python
from . import cron, push
from .store import Allowlist, Store
```
with
```python
from . import cron, push
from .outbox import Outbox
from .sender import SendQueue
from .store import Allowlist, Store
```

(b) Replace the `Watcher.__init__` signature line
```python
    def __init__(self, root: Path, store: Store, enabled: Callable[[Path], bool] = profile_enabled,
                 now: Callable[[], float] = time.time):
```
with
```python
    def __init__(self, root: Path, store: Store, enabled: Callable[[Path], bool] = profile_enabled,
                 now: Callable[[], float] = time.time, sender=None, signature=None):
```
and, right after the line `self.now = now` inside `__init__`, add:
```python
        # Where pushes go: a SendQueue (the leader makes one), or None to post straight to ntfy, as in tests.
        self.sender = sender
        self._own_sender = False
        self._sender_client: Optional[httpx.Client] = None
        # Optional: signature(home) says whether a profile's database changed (see db_signature). None: always read.
        self.signature = signature
        self._seen: Dict[str, tuple] = {}
```

(c) In `_run`, replace
```python
        self._marks = self.store.marks()
        with httpx.Client(timeout=httpx.Timeout(15.0, connect=10.0), follow_redirects=False) as client:
            while not self._stop.is_set():
                try:
                    self.poll(client)
                except Exception:  # never let one bad poll end the watcher
                    log.exception("herald-push: poll failed")
                self._stop.wait(POLL_SECONDS)
```
with
```python
        self._marks = self.store.marks()
        if self.sender is None:
            self.sender = self._make_sender()
            self._own_sender = True
        with httpx.Client(timeout=httpx.Timeout(15.0, connect=10.0), follow_redirects=False) as client:
            while not self._stop.is_set():
                try:
                    self.poll(client)
                except Exception:  # never let one bad poll end the watcher
                    log.exception("herald-push: poll failed")
                    self._seen.clear()  # read everything again next time
                self._stop.wait(POLL_SECONDS)
```

(d) Add this method after `stop`:
```python
    def _make_sender(self) -> SendQueue:
        """The leader's send queue: queued pushes left by an earlier run are delivered first."""
        self._sender_client = httpx.Client(timeout=httpx.Timeout(10.0, connect=5.0), follow_redirects=False)
        outbox = Outbox(self.store.root / "outbox.json", now=self.now)
        queue = SendQueue(self.store, outbox, client=self._sender_client, now=self.now)
        queue.start()
        return queue

    def _deliver(self, device, payload: dict, sig, client: Optional[httpx.Client]) -> bool:
        """Hands a push on: to the send queue when there is one (True = queued), else straight to ntfy."""
        if self.sender is not None:
            return self.sender.submit(device, payload, sig)
        return push.publish(device, payload, sig, client)
```

(e) In `stop`, replace
```python
        self._thread = None
        if self._marks_dirty:
```
with
```python
        self._thread = None
        if self._own_sender and self.sender is not None:
            self.sender.stop()
            self.sender = None
            self._own_sender = False
            if self._sender_client is not None:
                self._sender_client.close()
                self._sender_client = None
        if self._marks_dirty:
```

(f) In `poll`, replace
```python
                    if not allow.allows(device.device_id, profile):
                        continue
                    if push.publish(device, payload, sig, client):
```
with
```python
                    if not allow.allows(device.device_id, profile):
                        continue
                    if self._deliver(device, payload, sig, client):
```
and in `poll_cron` replace
```python
                    status=run.status, notify=notify, text=push.clip(run.text), at=int(run.at),
                )
                if push.publish(device, payload, sig, client):
```
with
```python
                    status=run.status, notify=notify, text=push.clip(run.text), at=int(run.at),
                )
                if self._deliver(device, payload, sig, client):
```
Also change the `poll` docstring `returns how many pushes went out` to `returns how many pushes were handed on for delivery`.

- [ ] **Step 4: Run to verify they pass, then the whole suite**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: all pass, including the 192 existing tests (they use `sender=None`).

- [ ] **Step 5: Commit**
```bash
git add herald_push/watcher.py tests/test_watcher_queue.py
git commit -m "watcher: deliver through the send queue; the leader owns the outbox"
```
(append the two trailer lines)

---

### Task 5: Don't re-read a database that hasn't changed

**Files:**
- Modify: `herald_push/watcher.py` (add `import os`, `FULL_READ_SECONDS`, `db_signature`, `Watcher._unchanged`, edit `poll`)
- Modify: `herald_push/plugin.py` (pass `signature=db_signature`)
- Test: `tests/test_watcher_skip.py`

**Interfaces:**
- Consumes: `Watcher.signature` attribute (Task 4).
- Produces: `watcher.FULL_READ_SECONDS = 60`; `watcher.db_signature(home: Path) -> Optional[tuple]` (`(mtime_ns, size)` of `state.db` and `state.db-wal`, `None` for a missing file, whole result `None` on other OS errors); `Watcher._unchanged(profile: str, sig) -> bool`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_watcher_skip.py`:
```python
import os
import sqlite3

from herald_push import push, watcher
from herald_push.store import Store


def _make_db(home):
    home.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(home / "state.db")
    conn.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, content TEXT,"
                 " tool_calls TEXT, active INTEGER DEFAULT 1, display_kind TEXT, timestamp REAL)")
    conn.commit()
    conn.close()


def _setup(tmp_path, monkeypatch, make_device, clock, tip):
    root = tmp_path / "hermes"
    _make_db(root)
    reads = []

    def fake_tip(home):
        reads.append(home)
        return tip(len(reads))

    monkeypatch.setattr(watcher, "bot_chat_tip", fake_tip)
    monkeypatch.setattr(push, "publish", lambda *a, **k: True)
    store = Store(tmp_path / "data")
    store.upsert(make_device())
    signature = {"v": ("a",)}
    w = watcher.Watcher(root, store, now=clock, signature=lambda home: signature["v"])
    return w, reads, signature


def test_an_unchanged_database_is_not_read_again(tmp_path, monkeypatch, make_device, clock):
    w, reads, signature = _setup(tmp_path, monkeypatch, make_device, clock, lambda n: "s1")
    w.poll()
    w.poll()
    assert len(reads) == 1
    signature["v"] = ("b",)
    w.poll()
    assert len(reads) == 2
    w.poll()
    assert len(reads) == 2


def test_it_is_read_again_after_a_minute_whatever_the_signature_says(tmp_path, monkeypatch, make_device, clock):
    w, reads, _ = _setup(tmp_path, monkeypatch, make_device, clock, lambda n: "s1")
    w.poll()
    clock.t += watcher.FULL_READ_SECONDS - 1
    w.poll()
    assert len(reads) == 1
    clock.t += 1
    w.poll()
    assert len(reads) == 2


def test_a_read_that_failed_is_tried_again_at_once(tmp_path, monkeypatch, make_device, clock):
    def tip(n):
        if n <= 2:
            raise OSError("database is locked")
        return "s1"

    w, reads, _ = _setup(tmp_path, monkeypatch, make_device, clock, tip)
    w.poll()
    w.poll()
    w.poll()
    assert len(reads) == 3  # the signature never changed, yet each failed read was repeated
    w.poll()
    assert len(reads) == 3  # and once it worked, the unchanged database is left alone


def test_a_poll_that_raised_forgets_what_it_saw(tmp_path, monkeypatch, make_device, clock):
    w, reads, _ = _setup(tmp_path, monkeypatch, make_device, clock, lambda n: "s1")
    w.poll()
    w._seen.clear()  # what _run does after a poll raised
    w.poll()
    assert len(reads) == 2


def test_without_a_signature_function_everything_is_read_every_time(tmp_path, monkeypatch, make_device, clock):
    w, reads, _ = _setup(tmp_path, monkeypatch, make_device, clock, lambda n: "s1")
    w.signature = None
    w.poll()
    w.poll()
    assert len(reads) == 2


def test_db_signature_follows_the_database_and_its_log(tmp_path):
    home = tmp_path / "h"
    home.mkdir()
    assert watcher.db_signature(home) == (None, None)
    (home / "state.db").write_bytes(b"x" * 10)
    first = watcher.db_signature(home)
    assert first[0] is not None and first[1] is None
    (home / "state.db-wal").write_bytes(b"y")
    second = watcher.db_signature(home)
    assert second != first
    stat = os.stat(home / "state.db")
    os.utime(home / "state.db", ns=(stat.st_atime_ns, stat.st_mtime_ns + 1_000_000_000))
    assert watcher.db_signature(home) != second
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/Scripts/python -m pytest tests/test_watcher_skip.py -q --basetemp=.pytest-tmp`
Expected: FAIL (`module 'herald_push.watcher' has no attribute 'FULL_READ_SECONDS'`).

- [ ] **Step 3: Implement**

In `herald_push/watcher.py`:

(a) Add `import os` to the imports (alphabetical, before `import re`).

(b) Below `STALE_SECONDS = 15 * 60` add:
```python
# A profile whose database looks unchanged is read anyway this often, as a safety net for a signature that missed a write.
FULL_READ_SECONDS = 60
```

(c) Add, after `profile_homes` and before `bot_chat_tip`:
```python
def db_signature(home: Path):
    """What changes when a profile's ``state.db`` is written: the modification time and size of the database and
    its write-ahead log. A missing file is None; any other error makes the whole signature None (always read)."""
    parts = []
    for name in ("state.db", "state.db-wal"):
        try:
            stat = os.stat(home / name)
        except FileNotFoundError:
            parts.append(None)
        except OSError:
            return None
        else:
            parts.append((stat.st_mtime_ns, stat.st_size))
    return tuple(parts)
```

(d) Add this method to `Watcher` next to `_deliver`:
```python
    def _unchanged(self, profile: str, sig) -> bool:
        """True when a profile's database has the signature it had when it was last read, and that was under a
        minute ago."""
        if self.signature is None or sig is None:
            return False
        last = self._seen.get(profile)
        return last is not None and last[0] == sig and self.now() - last[1] < FULL_READ_SECONDS
```

(e) In `poll`, replace
```python
        for profile, home in profile_homes(self.root):
            try:
                tip = bot_chat_tip(home)
            except Exception:
                log.debug("herald-push: can't read %s", profile, exc_info=True)
                continue
            if not tip:
                continue
```
with
```python
        for profile, home in profile_homes(self.root):
            # Taken before the read, so a write that lands during it shows up as a change next time.
            sig = self.signature(home) if self.signature is not None else None
            if self._unchanged(profile, sig):
                continue
            try:
                tip = bot_chat_tip(home)
            except Exception:
                log.debug("herald-push: can't read %s", profile, exc_info=True)
                continue
            self._seen[profile] = (sig, self.now())
            if not tip:
                continue
```

In `herald_push/plugin.py` change
```python
from .watcher import Watcher, is_long_lived_process
```
to
```python
from .watcher import Watcher, db_signature, is_long_lived_process
```
and
```python
            _watcher = Watcher(root, store)
```
to
```python
            _watcher = Watcher(root, store, signature=db_signature)
```

- [ ] **Step 4: Run to verify they pass, then the whole suite**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: all pass.

- [ ] **Step 5: Commit**
```bash
git add herald_push/watcher.py herald_push/plugin.py tests/test_watcher_skip.py
git commit -m "watcher: skip a profile whose database hasn't changed (re-read at least every minute)"
```
(append the two trailer lines)

---

### Task 6: One command router, one JSON parser

**Files:**
- Modify: `herald_push/commands.py`
- Test: `tests/test_command_router.py`

**Interfaces:**
- Produces: `commands.op_and_json(raw: str, reply) -> Tuple[str, object, Optional[str]]` returning `(op, data, error_reply_or_None)`; `make_handler` keeps its exact signature `make_handler(store, version, watching, trusted=trusted_surface, phones=None, root=None)`. All command inputs and JSON answers stay byte-identical.

- [ ] **Step 1: Write the characterization tests (they must PASS on the unmodified code)**

Create `tests/test_command_router.py`:
```python
import json

import pytest

from herald_push import commands
from herald_push.phone import Phones
from herald_push.store import Store


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / "data")


def answer(handle, raw):
    return json.loads(handle(raw))


def handler(store, **kwargs):
    return commands.make_handler(store, "t", lambda: True, trusted=lambda: True, **kwargs)


def test_an_unknown_operation_gets_the_usage(store):
    assert answer(handler(store), "bogus") == {"ok": False, "error": commands.USAGE}


def test_the_trust_check_comes_first(store):
    handle = commands.make_handler(store, "t", lambda: True, trusted=lambda: False)
    assert answer(handle, "bogus")["error"].startswith("Herald push can only be managed")


def test_too_long_is_refused(store):
    assert answer(handler(store), "register " + "x" * 9000) == {"ok": False, "error": "too long"}


def test_register_that_isnt_json_says_so(store):
    assert answer(handler(store), "register nope") == {"ok": False, "error": "not JSON"}


def test_the_empty_command_is_info(store):
    handle = handler(store)
    assert answer(handle, "") == answer(handle, "info")
    assert answer(handle, "info")["ok"] is True


@pytest.mark.parametrize("raw", ["cron inbox nope", "profiles get nope"])
def test_namespaces_report_arguments_that_arent_json(store, raw):
    assert answer(handler(store), raw) == {"ok": False, "error": "not JSON"}


@pytest.mark.parametrize("raw", ["cron inbox [1]", "profiles get 5"])
def test_namespaces_want_an_object_with_a_device_id(store, raw):
    assert answer(handler(store), raw) == {"ok": False, "error": "bad device_id"}


def test_the_phone_namespace_exists_only_with_phones(store):
    assert answer(handler(store), "phone tools {}") == {"ok": False, "error": commands.USAGE}
    with_phones = handler(store, phones=Phones(store))
    assert answer(with_phones, "phone tools nope") == {"ok": False, "error": "not JSON"}
    assert answer(with_phones, "phone tools [1]") == {"ok": False, "error": "not an object"}


def test_op_and_json_splits_and_parses(store):
    reply = lambda **data: json.dumps(data)  # noqa: E731
    assert commands.op_and_json('get {"a": 1}', reply) == ("get", {"a": 1}, None)
    op, data, bad = commands.op_and_json("get nope", reply)
    assert op == "get" and data is None and json.loads(bad) == {"ok": False, "error": "not JSON"}
    assert commands.op_and_json("get null", reply) == ("get", None, None)
```

- [ ] **Step 2: Run against the UNMODIFIED code**

Run: `.venv/Scripts/python -m pytest tests/test_command_router.py -q --basetemp=.pytest-tmp`
Expected: everything passes **except** `test_op_and_json_splits_and_parses` (no `op_and_json` yet). If any other test fails, the characterization is wrong: fix the test to match the current behavior before refactoring.

- [ ] **Step 3: Refactor**

In `herald_push/commands.py`:

(a) Change the typing import to include `Tuple`:
```python
from typing import Callable, Optional, Tuple
```

(b) Add this helper directly above `def phone_command`:
```python
def op_and_json(raw: str, reply: Callable[..., str]) -> Tuple[str, object, Optional[str]]:
    """Splits ``<op> <json>`` and parses the JSON. Returns (op, data, None), or (op, None, an error answer) when the
    argument isn't JSON. ``data`` may be any JSON value, so callers still check its shape."""
    op, _, rest = raw.partition(" ")
    try:
        return op, json.loads(rest.strip()), None
    except ValueError:
        return op, None, reply(ok=False, error="not JSON")
```

(c) In each of `phone_command`, `cron_command` and `profiles_command`, replace this five-line block (it is identical in all three; use replace-all):
```python
    op, _, rest = raw.partition(" ")
    try:
        data = json.loads(rest.strip())
    except ValueError:
        return reply(ok=False, error="not JSON")
```
with
```python
    op, data, bad = op_and_json(raw, reply)
    if bad is not None:
        return bad
```
The lines after each block (the `isinstance` checks and their error answers) stay as they are.

(d) Replace the whole `make_handler` function (from `def make_handler(` to the end of the file) with:
```python
def make_handler(store: Store, version: str, watching: Callable[[], bool],
                 trusted: Callable[[], bool] = trusted_surface,
                 phones: Optional[Phones] = None, root: Optional[Path] = None) -> Callable[[str], str]:
    def reply(**data) -> str:
        return json.dumps(data, separators=(",", ":"))

    def info(rest: str) -> str:
        return reply(ok=True, version=version, gateway=store.public_identity(), watching=watching(),
                     devices=len(store.devices()), phone_tools=sorted(TOOLS), features=FEATURES)

    def register(rest: str) -> str:
        try:
            data = json.loads(rest)
            device = Device.parse(data)
            # The phone's allowed profiles travel with the registration, so a renewed phone is limited
            # from its first push. Without them (an older app), a list it already has stays as it is.
            profiles = data.get("profiles") if isinstance(data, dict) else None
            store.upsert(device, profiles)
        except ValueError as exc:  # InvalidRegistration and JSON errors
            return reply(ok=False, error=str(exc) if isinstance(exc, InvalidRegistration) else "not JSON")
        log.info("herald-push: registered %s", device.name)
        return reply(ok=True, version=version, gateway=store.public_identity(), watching=watching(),
                     features=FEATURES, profiles=store.allowed_profiles(device.device_id))

    def unregister(rest: str) -> str:
        return reply(ok=store.remove(rest))

    def test(rest: str) -> str:
        device = store.get(rest)
        if device is None:
            return reply(ok=False, error="not registered")
        _, _, sig = store.identity()
        return reply(ok=push.publish(device, push.message("ping"), sig))

    # One entry per operation; "phone" exists only when this gateway has phone tools.
    ops: dict = {
        "": info, "info": info, "register": register, "unregister": unregister, "test": test,
        "cron": lambda rest: cron_command(store, rest, reply, root),
        "profiles": lambda rest: profiles_command(store, rest, reply, phones),
    }
    if phones is not None:
        ops["phone"] = lambda rest: phone_command(phones, rest, reply)

    def handle(raw: str) -> str:
        # Exceptions would be swallowed into a generic "unknown command": always answer.
        try:
            if not trusted():
                return reply(ok=False, error="Herald push can only be managed from the Herald app or the dashboard.")
            raw = (raw or "").strip()
            if len(raw) > (MAX_ANSWER_ARG if raw.startswith("phone answer ") else MAX_ARG):
                return reply(ok=False, error="too long")
            op, _, rest = raw.partition(" ")
            operation = ops.get(op)
            if operation is None:
                return reply(ok=False, error=USAGE)
            return operation(rest.strip())
        except Exception as exc:
            log.exception("herald-push: command failed")
            return reply(ok=False, error=type(exc).__name__)

    return handle
```

- [ ] **Step 4: Run to verify, then the whole suite**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: all pass, including `test_op_and_json_splits_and_parses` and every existing command test (they pin the answers).

- [ ] **Step 5: Commit**
```bash
git add herald_push/commands.py tests/test_command_router.py
git commit -m "commands: a handler table and one JSON parser, answers unchanged"
```
(append the two trailer lines)

---

### Task 7: Announce it, version it, document it, test it in CI

**Files:**
- Modify: `herald_push/commands.py` (`FEATURES`), `herald_push/plugin.py` (`VERSION`), `plugin.yaml` (`version`, `homepage`), `README.md`
- Create: `CHANGELOG.md`, `.github/workflows/test.yml`
- Test: `tests/test_command_router.py` (append)

**Interfaces:**
- Produces: `FEATURES == ["profile_allowlist", "reliable_push"]`; version `0.7.0` in `plugin.yaml` and `plugin.py` (existing tests fail if they differ).

- [ ] **Step 1: Write the failing test**

Append to `tests/test_command_router.py`:
```python
def test_info_announces_reliable_push_next_to_the_profile_allowlist(store):
    features = answer(handler(store), "info")["features"]
    assert "reliable_push" in features and "profile_allowlist" in features
```
Run: `.venv/Scripts/python -m pytest tests/test_command_router.py -q --basetemp=.pytest-tmp` — expected FAIL on that test.

- [ ] **Step 2: Implement**

- `herald_push/commands.py`: `FEATURES = ["profile_allowlist"]` becomes `FEATURES = ["profile_allowlist", "reliable_push"]`.
- `herald_push/plugin.py`: `VERSION = "0.6.0"` becomes `VERSION = "0.7.0"`.
- `plugin.yaml`: `version: 0.6.0` becomes `version: 0.7.0`; `homepage: https://github.com/Giton22/hermes-herald-push` becomes `homepage: https://github.com/BookahCoding/hikyaku-push`.
- Create `CHANGELOG.md`:
```markdown
# Changelog

## 0.7.0

Reliability. No change to the wire format or to what the app sends.

- Pushes go through a per-phone send queue. A slow or unreachable ntfy server now delays only the phones that use it.
- A push that ntfy didn't take is kept on disk (`outbox.json`, sealed, never the plaintext) and retried after 5 s, 30 s, 5 min, then hourly. Items older than 24 h are dropped, and a phone keeps at most 200.
- An answer retrying can't fix (most 4xx) is dropped instead of retried forever.
- A bot's database is not read again while it is unchanged (still re-read at least once a minute).
- `info` and `register` answers list the `reliable_push` feature.
- Internal: `/herald-push` dispatch is a handler table with one shared JSON parser.

## 0.6.0 and earlier

See the history of the repository this plugin continues: https://github.com/Giton22/hermes-herald-push
```
- `README.md`: replace the opening paragraph's Herald link base and the install URL as needed, and add this section before "Which profiles a phone hears from (0.6.0)":
```markdown
## Delivery (0.7.0)

Each message is sealed once and put in a small queue (`outbox.json` in the plugin's data folder, mode 0600, holding only the encrypted envelope). One worker per phone posts it to ntfy. If ntfy can't be reached, or answers with an error that may pass, the message is retried after 5 s, 30 s, 5 min, then every hour, for up to 24 hours (the phone ignores anything older). A message is delivered at most once per attempt and may arrive twice if the plugin stops after ntfy took it; the phone drops repeats by message id. A phone that is removed, or registers again with new topics, is sent nothing from before.
```
Also change the install command in the README to `hermes plugins install https://github.com/BookahCoding/hikyaku-push` and add one line under the title: `Continues [Giton22/hermes-herald-push](https://github.com/Giton22/hermes-herald-push) (MIT).`
- Create `.github/workflows/test.yml`:
```yaml
name: tests
on:
  push:
  pull_request:
jobs:
  test:
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, windows-latest]
        python: ["3.11", "3.13"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: ${{ matrix.python }}
      - run: python -m pip install -r requirements-dev.txt
      - run: python -m pytest -q
```

- [ ] **Step 3: Run the whole suite**

Run: `.venv/Scripts/python -m pytest -q --basetemp=.pytest-tmp`
Expected: all pass (the existing version-agreement tests read `plugin.yaml` and `plugin.py`).

- [ ] **Step 4: Commit**
```bash
git add -A
git commit -m "0.7.0: reliable_push feature, changelog, CI, docs"
```
(append the two trailer lines)

---

### Task 8: Publish and release (STOP: needs the user's go-ahead)

Do not start this task until the user says to publish. Tasks 0-7 touch only the local clone.

- [ ] **Step 1: Ask for the go-ahead**

Show the user `git log --oneline` of the local repo and the `CHANGELOG.md` entry. Ask: create the public repo `BookahCoding/hikyaku-push` and push? Wait for yes.

- [ ] **Step 2: Create the repo and push the history**
```bash
cd "D:/Projects/hikyaku-push"
gh repo create BookahCoding/hikyaku-push --public --source=. --remote=origin \
  --description "Hermes plugin: end-to-end encrypted push and phone tools for the Hikyaku Android app"
git push -u origin master
```
Expected: the repo exists, public, with the full history (Giton22's commits included).

- [ ] **Step 3: Wait for CI**

Run: `gh run watch --exit-status` (or `gh run list --limit 1`)
Expected: all four matrix jobs green. If a job fails, report it with the log; do not tag.

- [ ] **Step 4: Tag and release**
```bash
git tag -a v0.7.0 -m "0.7.0: reliable delivery"
git push origin v0.7.0
gh release create v0.7.0 --title "0.7.0: reliable delivery" --notes-file CHANGELOG.md
```

- [ ] **Step 5: Hand back, do not deploy**

Tell the user the release is out and the commit hash. Staged install on the Asus gateway and the Hikyaku app's switch to the new URL and pinned commit are separate steps that need their own approval and are not part of this plan.

---

## Self-Review

- **Spec coverage (section 3):** send queue (Task 3, 4); outbox with backoff/expiry/cap (Task 2); sealed-once idempotency (Tasks 2, 3); cheaper polling with 60 s safety net (Task 5); command router (Task 6); `reliable_push` feature and version (Task 7); testing items (failure injection in Tasks 2, 3, 5; envelope compatibility is untouched because `envelope_for` and `crypto` are not modified and the existing suite pins them); repo creation, CI and tag (Task 8); staged gateway install is explicitly out and gated. The spec's single-version-source item was dropped because existing tests already enforce it (spec updated).
- **Placeholders:** none; every code step has full code.
- **Type consistency:** `push.OK/RETRY/DROP`, `push.post(device, body, client)`, `Outbox.add/head/done/retry_later/device_ids/drop_device`, `SendQueue.submit/start/stop/step`, `Watcher(..., sender=None, signature=None)`, `_deliver`, `_unchanged`, `db_signature`, `op_and_json` are used with the same names and signatures across tasks.
- **Known limits to state at release:** `plugin.wake` (phone-tool wake pushes) and the `test` command still post synchronously, because they run outside the leader process; moving them is future work.
