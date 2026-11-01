# Themes instead of accents, with matching app icons — design

Date: 2026-11-01. Status: awaiting review.

## Goal

Replace the accent-colour picker with a theme picker. Keep the look Chris uses now (Ukiyo-e), add three more
Hokusai-inspired themes and two Tokyo neon themes, and give each theme a launcher icon that matches it.

## What exists today

- `AccentPalette` (shared/designsystem) holds Blue, Violet, Green, Orange, Pink, Teal and **Ukiyo-e**. Ukiyo-e is the only
  one that also brings its own grounds, text and signal colours (`Neutrals`), for light, dark and pure black; the others use
  the shared graphite/off-white neutrals. Contrast is tested (`AccentPaletteTest`, `UkiyoeThemeTest`, `ThemeColorsTest`).
- `AppSettings.accent` is a preset name (a string); `AccentPalette.named(...)` reads it in App.kt, AssistantPanel, IncomingCall
  and previews. Settings has an `AccentPicker` of swatches (SettingsScreen.kt).
- `ThemeMode { System, Light, Dark }` plus a pure-black option pick the scheme. This stays as is.
- Launcher icons: `AppIcon` enum (Blue, Violet, Green, Orange, Pink, Teal = "Colors"; Hearts, Marks, Bubbles = "Patterns"),
  one `activity-alias` + `mipmap-anydpi/ic_launcher_*.xml` each, an `AppIconScreen` picker, and `AppIconsManifestTest`
  that keeps manifest and enum in step. `AppIcons.apply(icon)` switches the enabled alias and reopens Herald.

## Design

### 1. Themes replace accents

- `AccentPalette` is renamed `AppTheme` (name, id, and the same light / dark / black `AccentColors` + `Neutrals`). Every theme
  brings its own neutrals, so nothing falls back to the shared graphite set. `AppTheme.all` is the list below, in this order.
- `AppSettings.accent` keeps its storage key and type (a string) so saved settings still read. A saved name that is not a theme
  (Blue, Violet, Green, Orange, Pink, Teal, or anything unknown) reads as **Ukiyo-e**. No data migration is written back
  until the user picks a theme.
- The Settings `AccentPicker` becomes a `ThemePicker`: one card per theme showing a small preview (background, surface bar,
  accent chip, text line) in the colours of the scheme currently in force, the picked one ringed, the theme's name under it,
  each a selectable radio with the theme name as its description.

### 2. The six themes

Each has light, dark and pure-black variants; the existing System / Light / Dark switch and the pure-black option choose the variant.
Hex values are seeds: the implementer adjusts them until every text/background pair meets 4.5:1 (UI chrome 3:1) in the
contrast tests, and keeps the character.

| Theme | Light | Dark | Accent (light → dark) | Signals |
|---|---|---|---|---|
| **Ukiyo-e** (unchanged) | washi `#F4EDD9` | indigo `#0E1424` | vermilion `#A63F2D` → `#E5927E` | as now |
| **Great Wave** | foam cream `#F3EBD8`, text `#1B2A41` | Prussian `#0B1A33`, text `#EAE3CF` | wave blue `#1F4E8C` → `#7FB0E8` | success sea-green, warning ochre, danger coral |
| **Red Fuji** | dawn `#F7E9E1`, text `#2A1F1D` | warm charcoal `#1C1614`, text `#F2E4DA` | Fuji red `#B3402B` → `#E8826A` | success pine, warning amber |
| **Thirty-six Views** | celadon `#E6EDDF`, text `#1F2A22` | pine night `#0F1A14`, text `#E4EDE0` | pine `#2F6B4F` → `#6FBF8F`, ochre `#B8862B` secondary | danger brick |
| **Shibuya Night** | lilac `#F6F1FF`, text `#1B1233` | near-black violet `#0A0716`, text `#F3EEFF` | magenta `#C2187A` → `#FF7CC0` (fill `#FF3EA5`), cyan `#22E3FF` as secondary/signal | |
| **Akihabara** | cool grey `#EEF2F5`, text `#0E1A24` | black-blue `#060B12`, text `#E9F4F1` | green `#0B8A4A` → `#2BE37A`, violet `#8B5CFF` secondary | |

Pure-black variants pull each dark ground toward `#000000` as Ukiyo-e's does. The neon themes keep glow-free flat surfaces
(no blur or shadow effects); "neon" comes from saturated accents and near-black grounds.

### 3. Icons follow the theme

- `AppIcon` gains one icon per theme (`Ukiyoe`, `GreatWave`, `RedFuji`, `ThirtySixViews`, `Shibuya`, `Akihabara`): adaptive
  vector icons (background colour layer + the Herald mark foreground in the theme's accent), plus a motif where it is cheap in
  vector: a stylised wave crest on Great Wave, a Fuji cone on Red Fuji, a pine bough on Thirty-six Views, a neon outline
  of the mark on Shibuya and Akihabara. Each needs `mipmap-anydpi/ic_launcher_<name>.xml`, a manifest `activity-alias` and a
  row in `AppIconsManifestTest`'s expectations.
- The colour icons Blue…Teal are removed from the enum and manifest, **except** that the current default alias (the brand
  Blue icon, named after the activity's old name so existing home-screen shortcuts still resolve) stays as a non-pickable
  entry, so installs that still have it keep a working launcher entry; it is used until a theme is picked. The pattern icons
  (Hearts, Marks, Bubbles) stay in the existing icon screen as manual choices.
- Picking a theme sets its icon (a pattern choice made by hand sticks until the next theme pick). The switch must not reopen
  the app in the middle of choosing a theme: `AppIcons.apply` for a theme pick is deferred until Herald goes to the background
  (or is next opened), with `DONT_KILL_APP`; the picker shows "Icon changes when you leave the app". The explicit icon screen
  keeps today's behaviour (switch and reopen).

### 4. Not changing

`ThemeMode`, the pure-black switch, fonts, the design tokens, notification and shortcut icons, and the Wear app's colours.

## Testing

- designsystem: for each theme × variant, the contrast checks `AccentPaletteTest` / `UkiyoeThemeTest` already apply (extend, do
  not copy); a test that every theme has its own neutrals; `AppTheme.named("Blue")` and unknown names give Ukiyo-e.
- ui/settings: the theme picker lists every theme once and selection round-trips through `AppSettings.accent`;
  `AppIconsManifestTest` covers the new aliases and the kept default alias.
- Manual on the phone: pick each theme in light, dark and pure black; check Chat, Inbox, Settings and the incoming-call screen
  for unreadable pairs; check the icon changes once the app is left.

## Out of scope

User-defined themes, theme import/export, per-bot themes, animated icons, desktop Hikyaku themes (a separate repo).

## Open questions

- Whether to also offer the old colour accents as "Ukiyo-e with accent X". Decided no (the request was to replace them).
