# Chat UX and performance implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development task by task, with independent review and final integration checks.

**Goal:** Implement the UI/UX and performance improvements approved in chat and open a PR.
**Architecture:** Extend existing Compose controls, settings and attention navigation. Keep protocol state lossless; optimize rendering and add repeatable synthetic performance journeys.
**Tech stack:** Kotlin Multiplatform, Compose Unstyled, Android Gradle Plugin, AndroidX Benchmark.
**Spec:** The user's approval covers the ten proposals in the preceding conversation. Their concrete scope is captured below.

## Global constraints
- Preserve existing send, approval, reconnect and offline-cache semantics.
- Use synthetic data for screenshots and benchmarks; never modify the live gateway.
- Preserve 48 dp touch targets, both themes and 360 dp layouts with large fonts.
- Performance gains must be measured; report unavailable device evidence honestly.
- Work only on `codex/chat-ux-performance`; open a PR, do not merge.

## Review focus
- Attachments must queue when Steer is selected; choosing a mode must never send.
- Switching chats must not expose the previous chat's rendered text or attention state.
- Compact mode must retain access to typing, attachment removal, Stop and pending requests.
- Reading mode must preserve unread positioning and history pagination.
- Benchmark-only activities/data must not be exported in production releases.

### Task 1: Clearer controls and reading layout
Files: ChatScreen.kt, RunningSendLabels.kt, App.kt, SessionsSidebar.kt, OtherChatBanner.kt / existing SessionsButton, settings models/UI, focused tests and preview scenes.
- [ ] Add regression tests for send-mode selection versus submission, attachment fallback, compact expansion and persisted reading settings.
- [ ] Replace immediate-send segmented controls with a labelled Send action and an explicit mode menu. Menu selects only; button sends. Explain attachment queue fallback visibly.
- [ ] Connect a visible needs-you badge to the existing sidebar attention filter; reuse next-chat infrastructure and offer Next waiting task after requests are answered.
- [ ] Extend composer folding: compact while unfocused/empty, expanded for text, attachments, dictation or editing; keep Stop reachable and collapse verbose running details while reading back.
- [ ] Add a persisted reading mode in Chat settings: top-aligned transcript, folded reasoning/tools, reduced decoration. Keep default layout unchanged.
- [ ] Audit secondary chat information contrast and large-font/touch behavior; improve only problematic usages.
- [ ] Run focused tests and full relevant host suites, self-review, commit and report.

### Task 2: Reduce transcript rendering work
Files: ChatScreen.kt (list only), new rendering helpers if useful, MarkdownText.kt, MathMarkup helpers and relevant tests.
- [ ] Add tests for reusable message structure across text deltas and bounded preparation cache behavior including changed text.
- [ ] Add lazy-list content types for each message kind.
- [ ] Reuse structural list computations across streaming deltas without delaying critical events or losing text. Avoid introducing a protocol throttle.
- [ ] Prepare long Markdown/math content off the UI thread and cache bounded results. Keep small settled messages synchronous where useful and never show a prior message's content after reuse.
- [ ] Test cancellation/replacement and final text correctness; run relevant host suites, commit and report.

### Task 3: Reproducible performance journeys
Files: benchmark module, Gradle configuration, benchmark-only activity/fixtures, performance documentation.
- [ ] Add an app-specific Baseline Profile generator and Macrobenchmark journeys for startup, long chat scrolling, streaming while scrolling and session switching using synthetic data.
- [ ] Use a release-like benchmark target and keep fixture activities out of normal release manifests.
- [ ] Run generation and before/after comparisons when a suitable device is available; retain raw artifacts outside tracked source and record exact limitations otherwise.
- [ ] Document commands, metrics and fixture boundaries; compile benchmark and app variants, commit and report.

### Final integration
- [ ] Independent reviews, fixes, full CI checks, narrow/large-font and light/dark visual checks.
- [ ] Update roadmap and PR with exact behavior, validation and outstanding device limitations.
- [ ] Push branch and open draft PR; no merge.
