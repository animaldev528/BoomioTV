# NuvioTV fork — current state for TV-building agents

Last updated: 2026-09-04. **This file is committed to `fork/dev` so every worktree based on the fork auto-loads it — keep it current as `dev` moves.** Note: upstream NuvioMedia's `.gitignore` excludes `CLAUDE.md`; on this fork it is deliberately force-tracked as the agent brief, so a plain `git add CLAUDE.md` won't stage it — use `git add -f CLAUDE.md`.

## Single stream

`fork/dev` (animaldev528/NuvioTV) is the **only** integration line for the TV app. Every feature lands there via a PR that the **owner merges** (`!`). As of this note the tip is `b884beb52`, carrying **all** code PRs #2, #4–#9, #11–#14. The only PRs still open are docs drafts **#3** and **#10**.

Remotes on this clone: `origin` = NuvioMedia/NuvioTV (upstream), `fork` = animaldev528/NuvioTV (where PRs land).

**Ground rules for new work:**
- The local `dev` checkout here is frequently **stale** (it was `a8f58a463`, days behind the fork). Always `git fetch fork`, then branch from a fresh `fork/dev` — never from the local `dev` or from an old feature branch.
- Rebase onto the current `fork/dev` tip before opening/landing a PR, so the diff is clean.
- Do **not** push to `fork/dev` and do **not** merge your own PR — the owner lands everything via `!`.

## What's in `dev` now (build on top of these, don't reinvent)

- **Private listening → phone (#12)** — Roku-style TV→phone audio tee + companion protocol. Files: `ui/screens/player/PlayerRuntimeController*`, `PrivateListening*`, `PlaybackSpeedAwareAudioSink`, `core/boomio/CompanionPlaybackBridge`.
- **Device capability reporting (#6/#7/#14)** — probe → POST bsm `capability-report` (`device-caps/1`). Files: `core/device/*`, `data/remote/dto/DeviceCapabilityReportDto.kt`. Gotcha from #14: `Display.isWideColorGamutSupported` is **@hide** and won't compile — use the public `Display.isWideColorGamut()` (API 26+). `Build.SOC_*` needs the `SDK_INT >= S` (31) gate.
- **Like-bootstrap (#13) REPLACED the on-TV taste picker (#8)** — long-press → Like is the current liking path; the old taste-picker UI is gone. Files: `core/like/*`, `core/sync/*`, `data/local/LikePreferences`, `Home*` like actions, `ui/components/posteroptions/*`. Any follow-up (e.g. banner copy) is a **separate small PR on top of `dev`**.
- Also in `dev`: Search People strip (#4), cast filmography order + error handling (#5/#11), consistent long-press poster options (#9), kids walls + More-like-this for all profiles (#2), plus pre-PR-era work (anime hub, hub tab ribbons, kids MLT).

## Build / deploy

- Compile on the build box (.63): `:app:assembleFullRelease` (~8 min, TV). adb targets: `.53` demo Pi (1080p), `.59` firestick.
- Build secrets already live in this checkout (`local.properties`, `gradle.properties`, `nuviotv.jks`) — **never inline or commit them**, and never push them to the fork.
- Client↔server features have a **server half in boomio** (separate repo, `main`). A TV change may need a matching boomio PR — coordinate, don't assume the TV repo holds the whole feature.
