# Boomio client — porting the fork's work onto 1.0.0

Handoff for the agent who ports `fork/dev`'s work onto the `boomio` branch.
Status: **plan, not started.** Written 2026-09-27.

---

## 1. Where we are

Upstream shipped **1.0.0** (`9f17e8bf4`). The fork's `dev` (tip `20350225a`) had stopped
tracking upstream: it carries **58 commits of our own work** on top of the point where it
diverged (**merge-base `f37e7eeab`**), and is **386 commits behind** 1.0.0.
`git cherry -v 1.0.0 fork/dev` marks **all 58 with `+`** — not one has an equivalent patch
upstream, so none can be skipped on those grounds. (Caveat: `git cherry` compares patches,
so a commit can still be *functionally* redundant if upstream solved the same problem
differently. Only C4 is suspected of that — see §6.)

Rather than rebase 58 commits across 386 upstream commits, a clean branch was cut from the
tag:

- **branch `boomio`**, worktree `/opt/nuvio-tv/.claude/worktrees/boomio-1.0.0`, pushed to
  `fork/boomio` (`animaldev528/BoomioTV`).
- Base = tag `1.0.0`. Contains exactly four commits:

| sha | what |
|---|---|
| `333ca430a` | `.github/workflows/build-boomio.yml` — tracks upstream stables |
| `23b6bee17` | workflow hardening (injection, force/republish, publish-time secrets) |
| `5266d4ba0` | the `boomio` product flavor + branding overlay + updater repoint |
| `04d567bea` | flavour-scoped deps: `boomioImplementation extendsFrom fullImplementation` |

So `boomio` today builds **vanilla upstream with Boomio branding**. This document is the
plan to bring the 58 commits back.

"Rebase" here means a **series of cluster ports** — cherry-pick a coherent group, resolve
its conflicts once, build it, move on — not a `git rebase`.

## 2. Ground rules

1. **Never push to `origin` in this clone.** Here `origin` is `NuvioMedia/NuvioTV`, a third
   party. The fork is the **`fork`** remote (`animaldev528/NuvioTV`, GitHub-renamed to
   **BoomioTV**). Trap: *inside GitHub Actions* `origin` **is** the fork — which is why the
   workflow pushes to `origin/<branch>`. Two different meanings; check which context.
2. **Compile only on the build machine.** `root@192.168.68.56` (key `~/.ssh/buildbox`,
   "downstairs"). It moved from `.63` on 2026-09-27; a note saying `.63` means this box.
   Never build on the dev box — it OOMs.
3. **Keep `boomio` mergeable.** `build-boomio.yml` merges upstream's latest stable into this
   branch on a schedule; a conflict fails the run and opens an `upstream-sync` issue. So
   additive and surgical beats reformatting or renaming upstream files, and a flavor/overlay
   beats editing shared code wherever the change allows it. Every ported cluster is future
   merge-conflict surface.
4. **Do not re-port the identity rebrand** — §3.
5. **Preserve the protocol/server-identity tokens** — §4.
6. `local.properties` is gitignored and holds the build-time config. A new
   `buildConfigField` needs feeding from there, and the **CI copy carries only API keys**
   (deliberately no URLs), so anything not install-time must be added to it too.

## 3. `c864081f1` (identity rebrand) — do NOT port it wholesale

`boomio` implements identity as a **`boomio` product flavor + `app/src/boomio/` overlay**;
`c864081f1` does it in **`main/`**. The mechanism differs, so porting it *breaks* the flavor.

| Part of `c864081f1` | Verdict |
|---|---|
| `applicationId` + debug ids (`com.boomio.tv` / `com.boomiodebug.com`) | **superseded** — flavor sets identical values |
| `<data android:scheme="boomio">` | **superseded** — boomio uses `${deeplinkScheme}` |
| `DeepLinkParser` scheme check | **contradicts** — keep boomio's (landmine 2) |
| `app_name` in `main/res/values*/` | **superseded** by `src/boomio/res/values*/` |
| launcher icons / banners / brand colours (`main/res/mipmap-*`, `drawable*`) | **superseded** by `src/boomio/res/` |
| `core/server/{AddonWebPage,DebridFormatterWebPage,RepositoryWebPage}.kt` | **superseded** — boomio interpolates `R.string.app_name` |
| `SimklApiMetadata` default `"nuvio"→"boomio"` | **superseded** — flavor sets `BuildConfig.SIMKL_APP_NAME` |
| `GITHUB_OWNER`/`GITHUB_REPO` | **conflict, not superseded** — fork says `NuvioTV`, boomio says `BoomioTV`; **boomio's must win** |
| `NuvioApplication`→`BoomioApplication` rename | **not superseded — recommend SKIP** (landmine 1) |
| `README.md`, `CONTRIBUTING.md`, `docs/essential-mode.md`, `.github/ISSUE_TEMPLATE/config.yml`, `.github/PULL_REQUEST_TEMPLATE.md`, `assets/brand/*.png`, `DeepLinkParserTest.kt` | **not superseded** — boomio has none of these; but the test must be rebased onto boomio's parser |

No other commit touches icons, the scheme, or `DeepLinkParser`.

## 4. Tokens that must NOT be rebranded

Servers match on these; changing any is a coordinated backend change, not a find/replace:

User-Agents `NuvioTV/1.0` and `Nuvio/$version`; device-auth `CLIENT_NAME` "Nuvio TV"; the
TorBox device client name "Nuvio"; `/.well-known/nuvio`; the `nuvio-tv-` sync prefix;
`nuvio-addon-sub:` track ids; `api/nuvio/profile-ratings`; `nuvio-collections.json`; the
`nuvio.tv` http deep-link hosts; the official id `com.nuvio.app` in the README.

### Are these Nuvio-proper things incompatible with Boomio? No — verified 2026-09-27.

Three different things get conflated here, and only one needed checking:

1. **Protocol tokens** — User-Agents, `CLIENT_NAME` "Nuvio TV", `/.well-known/nuvio`, the
   `nuvio-tv-` sync prefix, `nuvio-addon-sub:`, `api/nuvio/profile-ratings`,
   `nuvio-collections.json`. These are *outbound* strings that our own servers match on. They
   don't touch the manifest, the build, or the application id, so nothing about the `boomio`
   flavor disturbs them and nothing about them disturbs the flavor. This is why the rebrand
   calls them "kept truthful".
2. **Outbound `nuvio.tv` http URLs** (login/link pages) — also outbound. The manifest declares
   **no `android:host` intent filters at all**, only `${deeplinkScheme}` and the shared
   `stremio` scheme, so Boomio claims no web links and cannot collide with the official app
   over them.
3. **The `nuvio://` scheme** — the only one that needed checking, and it resolves cleanly:
   - no source in the repo *produces* a `nuvio://` or `boomio://` URI, and `DeepLinkParser`
     has exactly two call sites, both parsing an incoming intent (`MainActivity.kt:806` and
     `:961`). The parser therefore only ever sees a scheme the manifest already routed to us;
   - with `${deeplinkScheme}`, the `boomio` flavor registers `boomio://` only, so a `nuvio://`
     link is delivered to the official Nuvio app and never reaches Boomio — the parser's
     extra `nuvio` acceptance is dead code there;
   - so the fork's stricter `if (scheme != "boomio") return null` is *not* a runtime problem
     for `boomio`, but it **is** wrong for the shared `main` source set, which compiles into
     all three flavors: `full`/`playstore` register `nuvio://`, so the fork's version would
     make them reject their own links. **Keep Boomio's flavor-agnostic parser.**

## 5. Clusters and port order

Ordered so each cluster's prerequisites are already on the branch.

| # | cluster | commits | size | why here |
|---|---|---|---|---|
| C1 | boomio seam: `BsmApi`, `BoomioStreamResolver`, rating gate, telemetry | `dbaa67af7 7088246d1 c18a7ec53 c269cd8ec 287168643 f86b60a2c` | 6c, 26f, +652/−31, 7 new | The integration base; C8 and C10 extend it |
| C2 | addon-manifest robustness | `a1fd256f8 d4b234c0e 3ae3acdc9 5e7b7a699` | 4c, 3f, +157/−31 | Self-contained, stabilises the addon path |
| C3 | player info overlay (URL + TTFB) | `e0ac3c9ae` | 1c, 5f, +53/−3 | Independent, small — good first port to prove the workflow |
| C4 | daily-show episode ordering | `7e3a99aee 6c0269353` | 2c, 6f, +215/−27 | **Verify redundancy with 1.0.0 first** (§6) |
| C10 | device-caps reporting | `7015ec651 ba00e8a64 0596d1f3a fb7550ede` | 4c, 10f, +1004/−17 | Needs C1 |
| C5 | Movies/TV hubs + browse + anime + long-press | `7c6e2d1d6 cddfcc8bb b51e0611b 5246aecc8 04ee7e933 4265c6f57 7221b6598 a8f58a463 7bd1e9e76 08b8106c3` | 10c, 22f, +2385/−1022, 9 new | Large; introduces `DrillExtraction` |
| C6 | kids walls | `f4ea47a42 2c4af99ec 63ab18280 9cbf5d835 ed34c8608` | 5c, 15f, +1625/−156, 8 new | Extends C5's hub rows |
| C7 | search People + cast filmography | `18f60ba72 99278865d 0bf971b78 f797c7d94` | 4c, 12f, +525/−26 | Independent |
| C8 | companion + watch party | `e90d009ab e5a99c944 324250d6c 32127ad60 d96ab1f00 0de0781f1` | 6c, 17f, +805/−23 | Needs C1 |
| C9 | private-listening tee | `3beb24870 781f96e6f 47ac77de4 7e872a84e 6d86a2509 ~~76c6caec0~~ 244f60956` | 7c, 9f, +808/−160 | Needs C8 (Slice B *is* a companion-protocol extension). **Take the net** — `244f60956` supersedes `76c6caec0` (landmine 6) |
| C11 | taste / like-bootstrap | ~~`9f51afb64`~~ `b884beb52 0d2e62b09 93ab868ad c0cd1f8da` | 5c, 37f, +2694/−1465 | Needs C5 and C6. **Owner decision 2026-09-27: port the net end state — skip `9f51afb64`**, which `b884beb52` replaces outright |
| C13 | docs / misc | `bf514eadd e529f96cd 4fc496e82` | 3c, 3f, +47/−2 | Anything left |
| C12 | identity rebrand | `c864081f1` | 1c, 100f | **Mostly skip** — §3; port only the docs/assets |

**Evidence-based dependencies** (not guesses):

- **C1 → C8, C10.** `c18a7ec53` *creates* `core/boomio/BoomioStreamResolver.kt` and
  `BsmApi.kt`; C8 adds to the same package, C10 modifies both.
- **C8 → C9.** `e5a99c944` creates `BoomioCompanionManager.kt` +
  `CompanionPlaybackBridge.kt`; four C9 commits modify both.
- **C5 → C11, C6 → C11.** C11 edits `H/{CategoryRowsScreen,CategoryRowsViewModel,DrillDown}`
  and `HB/HubBrowseViewModel` (all created by C5), `K/MoreLikeThis*` (created by C6), and
  `posteroptions/PosterOptionsDialog` (touched by C6).
- **C1 internal:** `dbaa67af7` adds the `activityEventReporter` param that `f86b60a2c`
  fixes tests for.
- Independent: C2, C3, C4, C7, C13.
- Undetermined: whether C4 is functionally redundant with 1.0.0.

## 6. Per-commit detail

**Risk rule used:** `T` = files the commit touches; `U` = files changed upstream in
`merge-base..1.0.0` (**300 files**); `ov = |T∩U| / |T|`. **low** = 0, **med** = ≤ 0.34,
**high** = > 0.34. Distribution: **19 low / 8 med / 31 high.**

⚠️ **Treat this as a lower bound.** The clone is **shallow** (graft root `7c3baa16e`; the
merge-base has only one ancestor present). If the true merge-base is deeper, `U` is larger
and every risk is understated — so read `med` as `high`. The ground truth is the
cherry-pick itself; the numbers are for sequencing, not for skipping verification.

Path codes: `P` player · `H` screens/home · `HB` screens/hub · `K` screens/kids ·
`S` screens/search · `N` navigation · `B` core/boomio · `D` core/device ·
`R` data/repository · `app` app/build.gradle.kts · `root` repo root.

```
sha       | subject                          | files +A/-D  n<new>/m<mod> | paths        | risk ov
dbaa67af7 | telemetry + watch-time           | 11f +246/-2   n2/m9       | P,root,app   | H 64
7088246d1 | BSM rating gate                  | 12f +214/-14  n4/m8       | H,profile,app| H 50
c18a7ec53 | boomio /find seam                |  3f +182/-5   n1/m2       | app,B,stream | H 67
c269cd8ec | CatalogSeeAll nullability fix    |  1f   +1/-3   n0/m1       | ui/screens   | L 0
a1fd256f8 | stop per-stream manifest refetch |  2f  +25/-16  n0/m2       | R,domain/repo| H 100
e0ac3c9ae | player overlay URL+TTFB          |  5f  +53/-3   n0/m5       | P,res        | H 80
d4b234c0e | manifest rate-limit hardening    |  2f +127/-13  n0/m2       | R            | H 100
3ae3acdc9 | null-safety retry merge          |  1f   +1/-1   n0/m1       | R            | H 100
5e7b7a699 | avoid nullable merge()           |  1f   +4/-1   n0/m1       | R            | H 100
7e3a99aee | daily shows recent-first         |  6f +189/-16  n2/m4       | detail,util  | H 67
f86b60a2c | SearchVM tests new param         |  2f   +2/-0   n0/m2       | test/S       | H 50
6c0269353 | daily default latest ep          |  1f  +26/-11  n0/m1       | detail       | H 100
7c6e2d1d6 | Movies/TV hubs + lean Home       | 11f +950/-33  n3/m8       | H,HB,N       | H 64
bf514eadd | gitignore APK                    |  1f   +2/-0   n0/m1       | root         | L 0
4fc496e82 | SourceSufficient meta leak       |  1f  +18/-2   n0/m1       | R            | H 100
287168643 | debug login self-host URL        |  1f   +7/-7   n0/m1       | app          | H 100
e90d009ab | BOOMIO_COMPANION_URL field       |  1f   +1/-0   n0/m1       | app          | H 100
e5a99c944 | companion manager N1             | 11f +478/-7   n2/m9       | P,B          | H 64
324250d6c | companion main-thread fix        |  1f  +20/-2   n0/m1       | B            | L 0
32127ad60 | party echo supp + max-res cap    |  6f  +64/-7   n0/m6       | B,app,R      | H 50
d96ab1f00 | companion scrub seek             |  1f  +29/-3   n0/m1       | B            | L 0
0de0781f1 | companion volume + kbd/voice     |  7f +213/-4   n0/m7       | B,S,manifest | H 57
cddfcc8bb | hub rows-browser + drill         | 18f +975/-864 n6/m9  d3   | H,HB,N       | H 39
b51e0611b | DrillExtraction compile fix      |  1f   +3/-3   n0/m1       | HB           | L 0
5246aecc8 | hub tab ribbon                   |  3f +187/-48  n0/m3       | HB,H         | L 0
04ee7e933 | ribbon compile fix               |  1f  +11/-6   n0/m1       | HB           | L 0
4265c6f57 | drill tile → material3 Card      |  1f  +28/-18  n0/m1       | H            | L 0
7221b6598 | Anime rail + ANIME hub           |  6f  +50/-4   n0/m6       | N,HB,app     | H 67
a8f58a463 | page-complete catalog rows       |  3f  +74/-32  n0/m3       | H,HB         | L 0
f4ea47a42 | kids walls (Leo)                 |  6f +605/-51  n2/m4       | N,K,app      | H 67
2c4af99ec | kids long-press MLT wall         | 13f +776/-2   n6/m7       | N,K,app      | M 31
63ab18280 | recursive MLT hides origin       |  8f +109/-32  n0/m8       | N,K,app      | H 38
9cbf5d835 | un-gate MLT all profiles         |  6f  +91/-52  n0/m6       | N,K,app      | H 50
7bd1e9e76 | bound page-complete fetches      |  1f  +31/-9   n0/m1       | H            | L 0
ed34c8608 | kids wall first-page retry       |  1f  +44/-19  n0/m1       | K            | L 0
18f60ba72 | search People strip              | 11f +464/-6   n2/m9       | S,test,tmdb  | H 55
99278865d | search named-arg fix             |  1f   +1/-1   n0/m1       | S            | H 100
0bf971b78 | cast filmography by fame         |  2f   +9/-1   n0/m2       | tmdb,cast    | H 100
7015ec651 | device-caps WS1/WS2 probe        |  5f +625/-0   n3/m2       | D,root,api   | M 20
ba00e8a64 | device-caps compile fix          |  1f   +1/-0   n0/m1       | D            | L 0
9f51afb64 | on-TV taste picker               | 13f+1482/-5   n6/m7       | taste,sync   | M 31  **SUPERSEDED**
08b8106c3 | long-press poster options        |  4f  +76/-5   n0/m4       | H,HB         | M 25
f797c7d94 | cast filmography error #12       |  3f  +51/-18  n0/m3       | tmdb,cast,res| H 100
3beb24870 | PL tee Slice A                   |  5f +506/-6   n2/m3       | P            | H 40
781f96e6f | PL companion protocol Slice B    |  5f  +93/-4   n0/m5       | P,B          | H 60
47ac77de4 | PL review fold-in F1/F2/F3       |  2f  +52/-3   n0/m2       | B,P          | L 0
7e872a84e | bestEffortLanIp fix              |  1f  +53/-9   n0/m1       | B            | L 0
6d86a2509 | re-arm PL over stale fork        |  1f  +10/-1   n0/m1       | B            | L 0
0596d1f3a | box SoC + sink gamut             |  3f  +62/-5   n0/m3       | D,dto        | L 0
b884beb52 | like-bootstrap                   | 20f +689/-1321 n4/m12 d4  | H,poster,taste| M 30  **SUPERSEDES 9f51afb64**
76c6caec0 | PL per-frame peak limiter        |  1f  +48/-3   n0/m1       | P            | L 0
244f60956 | PL 5.1/7.1 passthrough           |  1f  +46/-134  n0/m1       | P            | L 0  **CONTRADICTS 76c6caec0**
fb7550ede | auto-measure link + /find gate   |  7f +316/-12  n2/m5       | B,D,network  | M 29
e529f96cd | docs agent brief #15             |  1f  +27/-0   n1/m0       | root         | L 0
0d2e62b09 | taste look→like→Done #17         |  5f  +53/-12  n0/m5       | H,app,sync   | H 80
93ab868ad | MLT TMDB fallback #18            |  3f +108/-29  n0/m3       | K,app        | M 33
c0cd1f8da | rows in place + curated hubs #19 | 12f +362/-98  n0/m12      | H,N,app      | H 50
c864081f1 | identity-only rebrand            |100f+1243/-1227 n0/m100     | res,docs,srv | M 14  **SUPERSEDED-BY-FLAVOR**
```

## 7. Per-cluster execution recipes

Derived from the cluster diffs. **No cluster adds a Gradle dependency at all** (checked every
cluster diff for `*.gradle.kts` / `libs.versions.toml`) — so no dependency risk, and the
`boomioImplementation extendsFrom fullImplementation` fix stays sufficient. Two prerequisites
recur and both fail *silently*: see landmines 13–14.

**Forced order** (from the dependencies, not preference):
`C2 → C3 → C4 → C13` (independent) → `C1` (supplies `BOOMIO_BASE_URL`/`BSM_BASE_URL`) →
`C5` → `C6` (reuses C5's `MainActivity`/`NuvioNavHost`/`Screen`) → `C11` (edits C5's
`Screen.CategoryRows` call sites) → `C8` → `C10` (edits C8's `streamUrl` builder) → `C9` →
`C7` **last** (its `SearchViewModel`/`SearchScreen` hunks must resolve against C1's and C8's
versions).

---

**C1 — seam, rating gate, telemetry.** Pick `dbaa67af7 7088246d1 c18a7ec53 c269cd8ec
287168643` (+ `f86b60a2c` only if unit tests are in the gate). No skips.
Conflicts (14): `app/build.gradle.kts` (trivial — upstream only bumps version);
`core/di/NetworkModule.kt` (**semantic** — upstream rewrote the first-party cache dir and TLS
validation; C1 adds a `@Named("bsm")` Retrofit to the same Hilt module);
`core/player/ExternalPlaybackTracker.kt` (36 upstream, **semantic**);
`MetaDetailsViewModel.kt` (136 upstream, **semantic**);
`PlayerRuntimeController*.kt` + `PlayerViewModel.kt` (heavy, **semantic**);
`SearchViewModel.kt` (ctor param, **semantic**); `StreamScreenViewModel.kt` (121 upstream,
**semantic** — C1 adds a ctor param *and* rewrites the `mergedAddonStreams` block upstream
also rewrote). **Prereq:** introduces `BSM_BASE_URL` + `BOOMIO_BASE_URL`.
**Accept:** a profile with a BSM ceiling loses gated titles from the modern home carousel and
CatalogSeeAll; `adb logcat | grep -i bsm` shows `GET /api/nuvio/profile-ratings`.

**C2 — addon-manifest cache hardening.** Pick `a1fd256f8 d4b234c0e 5e7b7a699`; **skip
`3ae3acdc9`** (superseded by `5e7b7a699`, which rewrites the same line).
**Port only `AddonRepositoryImpl` — drop C2's two hunks on `StreamRepository.kt` /
`StreamRepositoryImpl.kt`: upstream's `78bc4635c` already fixed that same problem, with a
better shape** (`getStreamsFromAddon(addon: Addon, …)` reading `addon.displayName`/`logo`,
versus C2's `addonName: String, addonLogo: String?`). Keep upstream's form.
Conflicts: `AddonRepositoryImpl.kt` (5 upstream, **semantic** — same cache/revision machinery).
**Accept:** cold start shows **one** `GET …/manifest.json` per addon, no `429`, and the
stream log still prints the right addon display name.

**C3 — player HUD URL + TTFB.** Pick `e0ac3c9ae`. No skips.
Conflicts: `PlayerRuntimeControllerPlaybackEvents.kt` (131 upstream, **semantic**);
`PlayerUiState.kt`, `StreamInfoOverlay.kt`, `strings.xml` (textual).
**Accept:** play anything, open the stream-info overlay — URL and a TTFB row render.

**C4 — daily-show ordering.** Pick `7e3a99aee 6c0269353`. No skips.
Conflicts: `MetaDetailsViewModel.kt` (136 upstream, **semantic**);
`MetaDetailsUiState.kt` (default-settings line); `EpisodesSection.kt`/`MetaDetailsScreen.kt`
(textual). **Verify functional redundancy with 1.0.0 before porting** (open question 2).
**Accept:** a daily show's detail lists seasons descending with season 0 last and auto-selects
the newest episode, not S01E01.

**C5 — hubs + Anime rail + long-press.** Pick **all ten, in order**: `7c6e2d1d6 cddfcc8bb
b51e0611b 5246aecc8 04ee7e933 4265c6f57 7221b6598 a8f58a463 7bd1e9e76 08b8106c3`.
**This is not a net take.** `cddfcc8bb` deletes the `hub/HubModels.kt`,
`hub/HubScreen.kt` and `hub/HubViewModel.kt` files that `7c6e2d1d6` creates *and* rewrites the
same `NuvioNavHost` block, so it will not apply without its predecessor.
Conflicts: `MainActivity.kt` (**188 upstream commits**), `NuvioNavHost.kt` (108), `Screen.kt`
(51) — **semantic, and not settleable by reading**; home files moderate (12–84 changed lines).
**Accept:** left rail shows Movies/TV/Anime; hub opens with the genre ribbon; D-pad CENTER on
a drill tile opens `CategoryRows`; long-press on a poster opens the shared options dialog.

**C6 — kids walls.** Pick all five. **After C5**; resolve `MainActivity`/`NuvioNavHost`/
`Screen` in C5's favour. Conflicts: those same three files + `strings.xml`.
**Prereq:** `KIDS_PROFILE_IDS = setOf(3)` is hard-coded — Leo must sit at Nuvio
`profile_index == 3`.
**Content dependency:** `MoreLikeThisRepositoryImpl` calls
`{base}/more-like-this/{type}/{metaId}.json?skip=N` — a **bsf endpoint**. Confirm it is served
before expecting the walls to populate.
**Accept:** sign in as Leo — lands on the Library wall, not Home; long-press → More-like-this
opens a wall.

**C7 — People strip + cast fixes.** Pick `18f60ba72 99278865d 0bf971b78 f797c7d94`.
**Port last** (see the order above). Conflicts: `NuvioNavHost.kt` (108, **semantic**);
`SearchScreen.kt` (15); `SearchViewModel.kt` — also touched by C1, so a cross-cluster
conflict if C1 lands first; `TmdbMetadataService.kt`/`CastDetailScreen.kt` (textual).
**Accept:** blank search query renders a People strip; selecting a person opens CastDetail; a
failed filmography fetch shows an error state, not an empty list (issue #12).

**C8 — companion + watch party + max-resolution cap.** Pick all six in order.
**Depends on C1** for `BuildConfig.BOOMIO_BASE_URL` — and **both clusters insert
`BOOMIO_BASE_URL` into the same `defaultConfig` region**, so expect a duplicate-add conflict.
**Widest conflict set of any cluster:** the three nav files, `PlayerNavigationArgs.kt` (16
upstream, **semantic**), `PlayerRuntimeControllerObservers.kt` (101, **semantic**),
`PlayerViewModel.kt` (115), `SearchScreen.kt`/`SearchViewModel.kt`,
`StreamRepositoryImpl.kt` (33, **semantic** — C8 rewrites the `streamUrl` builder upstream
re-signatured), `NuvioApplication.kt` (textual).
**Prereq:** introduces `BOOMIO_COMPANION_URL` + `BOOMIO_MAX_RESOLUTION`.
**Accept:** phone pairs over `/ws`; starting a party from the phone shows the party indicator
on the TV; the bsf access log shows `max_resolution=…`.

**C9 — private-listening tee.** Pick `3beb24870 781f96e6f 47ac77de4 7e872a84e 6d86a2509
244f60956`; **skip `76c6caec0`**. Needs C8 (Slice B *is* the companion protocol).
Conflicts: `PlayerRuntimeControllerInitialization.kt` (15, **semantic**),
`PlayerRuntimeController.kt` (128, heavy context), `PlayerRuntimeControllerLifecycle.kt` (2),
`PlayerViewModel.kt` (115). `PlaybackSpeedAwareAudioSink.kt` is clean and already exists at
1.0.0. **Accept:** TV plays 5.1/7.1, phone gets **6-channel** output (not 2.0), TV speakers
stay muted.

**C10 — device-caps probe/reporter + link meter.** Pick `7015ec651 ba00e8a64 0596d1f3a
fb7550ede`. No skips (`ba00e8a64` is required — `mapNotNull` on `IntArray` doesn't compile).
**Depends on C8**: its diff rewrites C8's `maxResolution=` to `max_resolution=` and adds
`mobileCapMbps=`, so the hunk has nothing to patch without C8.
Reads `BOOMIO_MAX_RESOLUTION`/`BOOMIO_COMPANION_URL` (C8) and
`BOOMIO_BASE_URL`/`BSM_BASE_URL` (C1). Adds a `NetworkMeter` ctor param to
`StreamRepositoryImpl`, so the Hilt wiring needs updating by hand.
Conflicts: `StreamRepositoryImpl.kt` (33, **semantic**, same builder as C8),
`NuvioApplication.kt` (textual), `StreamRepositoryPluginIsolationTest.kt`.
**Accept:** bsm's Devices panel gains a row with network Mbps and max_resolution; the bsf log
shows `max_resolution=…&mobileMbps=…` on the stream path.

**C11 — like-bootstrap, net end state.** Pick `b884beb52 0d2e62b09 93ab868ad c0cd1f8da`;
**skip `9f51afb64`**. **After C5** — `c0cd1f8da` edits the `Screen.CategoryRows` route call
sites C5 introduced.
Conflicts (13): the three nav files (**semantic**); `core/sync/StartupSyncService.kt` (58,
**semantic**); `data/local/ProfileDataStore.kt` and `SupabaseModels.kt` (same lines as
upstream); home files + `strings.xml`.
**Accept:** on Jack's profile, long-press a poster → Like → Done refreshes rows in place; a
non-curated title's More-like-this answers from TMDB instead of an empty wall (#18).

**C13 — misc.** Pick `bf514eadd e529f96cd 4fc496e82`. Conflicts: `MetaRepositoryImpl.kt` —
overlapping lines but purely textual (unrelated concerns; no renamed symbol).
Note `CLAUDE.md` is new to this repo, so it conflicts on any future upstream merge that adds
one. **Accept:** a title no addon has, requested with and without `sourceAddonBaseUrl`, must
not return `SourceSufficient` from the source-aware in-flight `Deferred`.

**Two things this analysis could not settle** — they need a trial cherry-pick, not more
reading: (1) whether upstream's churn in `MainActivity.kt` / `NuvioNavHost.kt` / `Screen.kt`
(188/108/51 commits) is *semantically* incompatible with C5/C6/C8/C11 or merely textual;
(2) whether C5's `HubBrowseViewModel`/`CategoryRowsScreen` reference `HomeViewModel` members
that upstream renamed — the hub screens take a `homeViewModel` parameter, so only applying it
will tell.

## 8. Landmines

1. **`NuvioApplication` rename — skip it.** `c864081f1` renames it to `BoomioApplication`;
   `boomio` keeps `NuvioApplication`. Skipping is safe: the only fork-commit reference is a
   **KDoc comment** in `7015ec651`'s `DeviceCapabilityReporter.kt` — no compile impact.
2. **`DeepLinkParser` direct contradiction.** fork/dev has `if (scheme != "boomio") return
   null` (**rejects** `nuvio`); boomio has `if (scheme != "nuvio" && scheme != "boomio")`.
   Porting the fork version **breaks** the boomio flavor. Keep boomio's, and rebase
   `DeepLinkParserTest.kt` (from `c864081f1`) onto it rather than porting it.
3. **Updater repo conflict.** fork = `animaldev528/NuvioTV`, boomio = `animaldev528/BoomioTV`.
   Not a merge — **boomio's must win**, or updates hit the wrong repo.
4. **`applicationId` shape conflict.** `c864081f1` hardcodes `com.boomio.tv` in
   `defaultConfig`; boomio keeps `com.nuvio.tv` default + flavor override. Do not take its
   `app/build.gradle.kts`.
5. **"Compile-fix-only" is two categories, and only one is skippable.** This is easy to get
   wrong in both directions:
   - **Must KEEP whenever you port their parent** — each patches a compile error its
     in-cluster predecessor introduces, so skipping one leaves the branch uncompilable:
     `c269cd8ec` (fixes `7088246d1`), `5e7b7a699` (fixes `d4b234c0e`), `ba00e8a64` (fixes
     `7015ec651`), `b51e0611b` (fixes `cddfcc8bb`), `04ee7e933` (fixes `5246aecc8`),
     `99278865d` (fixes `18f60ba72`). `f86b60a2c` is test-compile only, so it is skippable
     *iff* unit tests are outside the gate.
   - **Genuinely skippable, because an in-cluster successor supersedes them outright:**
     `3ae3acdc9` (rewritten by `5e7b7a699`), `9f51afb64` (replaced by `b884beb52`),
     `76c6caec0` (replaced by `244f60956`).
6. **`76c6caec0` vs `244f60956` contradict.** The former adds a per-frame peak limiter to
   the TV-side stereo downmix; the latter (−134 lines) removes the downmix entirely for pure
   5.1/7.1 passthrough, making the limiter dead code. **Take the net** (`244f60956`).
7. **`9f51afb64` is fully superseded by `b884beb52`.** `ui/screens/taste/` does not exist in
   final fork/dev. Porting both churns four files in and back out.
8. **`7c6e2d1d6` is partially superseded by `cddfcc8bb`**, which deletes the
   `HB/{HubModels,HubScreen,HubViewModel}.kt` it created. Don't port `7c6e2d1d6` alone.
9. **Symbols 1.0.0 lacks, so order matters:** `DrillExtraction` +
   `DrillCategoryRow`/`DrillDown`/`CategoryRows*` (C5, consumed by C11), `MoreLikeThis*`
   repo/dto/model (C6), `BoomioStreamResolver` + `BsmApi` (C1, consumed by C8/C10),
   `BoomioCompanionManager`/`CompanionPlaybackBridge` (C8, consumed by C9), `NetworkMeter`
   (C10, consumed by `fb7550ede`), `LikeTarget`/`LikePreferences`/`TastePick` (C11).
10. **`0de0781f1` also edits `AndroidManifest.xml`** — adds
    `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />`. Port it;
    it doesn't collide with the `${deeplinkScheme}` edit but touches the same file.
11. **Don't use a range diff for C11.** Its span includes the taste files created by
    `9f51afb64` and deleted by `b884beb52`, so `git diff <first>^..<last>` shows files that
    don't exist in the net state. Port per-commit, or take the net diff of the cluster end
    state.
12. **The risk numbers are a lower bound** (shallow clone — see §6).
13. **Build-time properties fail silently.** `resolveProperty(...)` falls back to `""`, so a
    missing `local.properties` key does **not** fail the build — it inerts the feature. The
    build box's `/opt/nuvio-tv/local.properties` has `BSM_BASE_URL` and `BOOMIO_COMPANION_URL`
    but is **missing `BOOMIO_BASE_URL` and `BOOMIO_MAX_RESOLUTION`**, so C1's seam and C8's
    resolution cap would build green and do nothing — exactly the kind of failure that reads
    as "the ceilings aren't working". Add both before verifying C1 or C8. Same trap in CI: the
    `LOCAL_PROPERTIES_BASE64` secret carries keys only, so a *new* non-install-time value has
    to be added there too.
14. **C6 depends on a server route, not just on symbols.** `MoreLikeThisRepositoryImpl`
    fetches `{base}/more-like-this/{type}/{metaId}.json?skip=N` from bsf. If bsf does not serve
    that route, the walls render empty and it will look like a client bug.
15. **C5 → C6, in the *reverse* direction from §5's table** (found while porting C5,
    2026-09-27). §5 scores `08b8106c3` as `H,HB` and gives C5 no C6 prerequisite, but that
    commit adds the **consumer** side of `LocalMoreLikeThisNavigator` (import, `.current`
    lookup, `onMoreLikeThis` param + button in `HomePosterOptionsDialog`), while the
    **definition** and both `provides` sites come from C6's `2c4af99ec` — which touches
    `MainActivity.kt` only, and whose `provides` lambdas navigate to C6's `Screen.MoreLikeThis`.
    On the fork this hides because `2c4af99ec` is simply the older commit. **C5 landed the
    declaration as a provider-less seam** (`ec60af075`): `.current` is null, so Home's action
    stays hidden — the fork's own null case. **C6 must port `2c4af99ec`'s two `provides` blocks
    and the `MoreLikeThis` route to light it up**; nothing further is needed in
    `MainActivity.kt`. Read §5's "evidence-based dependencies" as *evidence-based, not
    exhaustive* — it also missed C1 → C5.
16. **Landmine 7 is too strong: `9f51afb64` is *partially* superseded, not "fully".** (Found
    while porting C11, 2026-09-27.) `b884beb52` does delete all four picker symbols
    (`ui/screens/taste/`), so skipping the commit is right about the **UI**. But it *keeps*
    and *reuses* two other things `9f51afb64` introduced, which the skip therefore dropped:
    `TastePickSyncService` + `TastePick` (byte-identical between `9f51afb64` and the C11
    window end, referenced by 10 files) and `ProfileManager.markProfileTasteCompleted`
    (9 lines, called by `TastePickSyncService:66`). Both had to be restored by hand
    (`af454cb7b`, `1e2d9dd00`), at the cost of two extra compile rounds. **When a commit is
    skipped because "X replaces it outright", grep the *callers* of what X keeps, not just
    the symbols X deletes.**
17. **`fork/dev`'s `testBoomioDebugUnitTest` does not compile for C11's classes — there is no
    upstream reference for the fix.** C11 adds required constructor params to
    `PosterOptionsController` (`likePreferences`, `likeSyncService`, `profileManager`) and
    `HomeViewModel` (`likePreferences`, `likeSyncService`, `tastePickSyncService`); five
    upstream test call sites build those objects by name and stopped compiling. The fork
    never updated them (last touch is `7c3baa16e`, an unrelated upstream merge; no fork commit
    adds the arguments), so the fork's unit-test task has been compile-broken since
    `b884beb52` and its CI evidently only builds APKs. **Repaired on the port branch**
    (`4af6681f4`) with relaxed `mockk`s. Corollary for every remaining cluster: on a class
    C11 touches, "the same N failures as base" is only evidence *because* the tests were
    repaired — an unrepaired compile break reports zero new failures while running nothing.
18. **§7 over-predicts C8's conflicts by a wide margin — and under-predicts one.** (Found while
    porting C8, 2026-09-27.) §7 calls C8 the "widest conflict set of any cluster" and names
    six semantic conflicts; **four files actually conflicted** (`Screen.kt`,
    `PlayerNavigationArgs.kt`, `NuvioNavHost.kt` on `e5a99c944`; `SearchScreen.kt` on
    `0de0781f1`). `StreamRepositoryImpl.kt`, `PlayerViewModel.kt`,
    `PlayerRuntimeControllerObservers.kt`, `NuvioApplication.kt` and `SearchViewModel.kt` all
    applied clean. The predicted `defaultConfig` duplicate-add did not happen either — the
    three `BOOMIO_*` fields merged onto adjacent lines (`build.gradle.kts:149/150/154`).
    **The three nav-file conflicts were NOT a C1 → C8 dependency** — see landmine 20, which
    corrects the first draft of this entry. Their cause is base-vs-source divergence, not a
    fork-internal dependency §5 could have listed.
    **Carry-forward for C10 (also corrected):** C8 *adds to* the `streamUrl` builder rather
    than re-signaturing it, which is why it merged clean where §7 expected a semantic
    conflict. This entry predicted C10's rewrite of that same builder would be "where the
    real collision sits". **It was not** — C10's four picks applied with zero conflicts
    (landmine 20).
19. **C8's max-resolution cap is inert unless the build sets the property — and it fails
    silently.** `buildConfigString(resolveProperty(…))` on a missing property yields `""`,
    not a build error, so a green build proves only that the code compiles. The build box's
    `local.properties` has **neither `BOOMIO_MAX_RESOLUTION` nor `BOOMIO_BASE_URL`** (landmine
    13), which means C1's seam *and* C8's cap both do nothing in the artifacts these branches
    produce. Set both before any on-device acceptance run of a stacked branch, or setup will
    be misread as breakage. Same trap in CI: an undefined property ships a release with an
    inert cap and no warning.
20. **The port's conflicts come from `boomio` and `fork/dev` having diverged — not from
    cluster-to-cluster dependencies.** (Found while porting C10, 2026-09-27; corrects
    landmine 18's first draft.) The first draft blamed C8's three nav-file conflicts on "C1
    puts `profileId` on the player route". **`profileId` on the player route is not in
    `fork/dev` at all** — `git log fork/dev -S profileId` over `PlayerNavigationArgs.kt`,
    `Screen.kt` and `NuvioNavHost.kt` returns nothing, and `git show e5a99c944^:…PlayerNavigationArgs.kt`
    has neither `profileId` nor `resumeFromMs`. It is `8e0c8c6fb` ("fix(sync): prevent
    cross-profile watch state leaks"), which is reachable from **`boomio`** but from
    **neither `main` nor `fork/dev`**.

    So the port base is a **third lineage**: `boomio` = 1.0.0 + the fork's own fixes, tracking
    a much newer upstream `dev` than `fork/dev`'s fork point. Every pick whose touched lines
    were changed on the `boomio` side since that fork point conflicts, and the resolution is
    usually "keep upstream's newer code, re-apply the fork's feature hunk" — which is what
    every resolution in this port has in fact been (C5's `ModernHomeContent.kt` focus
    relocation, C6's `SIDEBAR_AUTO_COLLAPSE_DELAY_MS`, C11's `getLocalizedContext`).

    **This means §5 is structurally blind to a whole class of this port's conflicts.** It is a
    fork-internal dependency analysis; in these cases the conflicting content is not in the
    fork, so no amount of patching §5 would surface it. Screen the base instead.

    **Scope this to 18 — do not over-apply it.** Landmines **15 and 16 are genuine §5 gaps**,
    not instances of this: C5's `08b8106c3` consuming C6's `2c4af99ec`, and landmine 7 being
    too strong, are both dependencies *between fork clusters*, and §5 — being exactly that
    kind of analysis — could have caught them. It missed them; it is not blind to them. The
    distinction is whether the conflicting content exists in the fork at all:

    | | who owns the conflicting content | is §5 the right tool? |
    |---|---|---|
    | 15, 16 | fork vs fork | **yes** — §5 was incomplete |
    | 18 (nav files) | `boomio` vs `fork/dev` | **no** — §5 cannot see it |

    Getting this backwards in either direction costs something: patching §5 for base
    divergence wastes effort, while dismissing a real fork-internal gap as "just divergence"
    loses the dependency the next porter needs.

    Scale of the divergence, as commits on `boomio` not on `fork/dev` touching one file:

    | file | boomio-only commits |
    |---|---|
    | `ui/navigation/NuvioNavHost.kt` | 127 |
    | `ui/navigation/Screen.kt` | 61 |
    | `ui/screens/player/PlayerNavigationArgs.kt` | 17 |
    | `ui/screens/search/SearchScreen.kt` | 16 |

    **Pre-flight screen before picking a cluster (use for C9/C7):**
    `git log --oneline boomio --not fork/dev -- <path>` for each file the cluster touches. A
    non-zero count does not predict a conflict — C10 touched `StreamRepositoryImpl.kt` (a hot
    file) and applied clean — but a *high* count in the specific region the hunk edits is the
    best available predictor. The per-commit replay remains the only exact answer.

### Port-fidelity check (use after every cluster)

A cluster's picks applying clean proves `git` matched *context*, not that the feature
survived. **Compare the added-line sets directly:** if the port carries the fork's commits
faithfully, the set of lines the fork's cluster added must be a subset of the set the port
added. With `F` = the cluster's real file list, `P` = the fork commit the port branched from,
and `T` = the cluster's tip in the fork:

```sh
# Per-commit, NOT a range: a cluster's commits are often non-contiguous in the fork.
: > /tmp/fork-added.txt
for c in <the cluster's fork commits>; do
  git show $c --format='' -- $F | grep '^+' | grep -v '^+++' \
    | sed 's/^+//' | sed 's/[[:space:]]*$//' >> /tmp/fork-added.txt
done
sort -u -o /tmp/fork-added.txt /tmp/fork-added.txt
git diff <port-parent>..HEAD -- $F | grep '^+' | grep -v '^+++' \
  | sed 's/^+//' | sed 's/[[:space:]]*$//' | sort -u > /tmp/ours-added.txt
comm -23 /tmp/fork-added.txt /tmp/ours-added.txt   # every line here needs an explanation
```

**Use the per-commit form.** A range (`<first>^..<last>`) is wrong whenever the cluster's
commits are not contiguous — C7's four are separated by the taste-picker work
(`9f51afb64`), and a range swept in that commit's whole TMDB API surface, producing 11
phantom "missing" lines (`@GET("person/popular")`, `popularPeople(`, …) that belong to a
different cluster entirely. C9's six *are* contiguous, so a range happened to be correct
there; do not generalize from that.

Run against C9 the corrected form returns **491 fork-added, 491 port-added, 0 missing**.
C7 returns 426 vs 429 with two lines to explain, and **both are artifacts, not gaps**:
`focusResults && index == 0 && !peopleVisible -> 0` is the conflict resolution's deliberate
rewrite, and `preferCrew = false` was *added by `18f60ba72` and removed by `99278865d`
within the same cluster* — our tree has the fork tip's final positional form
(`onNavigateToCastDetail(person.tmdbId, person.name, false)`), verified identical.

So three caveats, and this check is a **screen, not a proof**: it cannot see intra-cluster
churn (a line added then removed reads as missing), it cannot see a rewrite, and the +N
direction (we added more lines than the fork) is usually resolution comments. Treat every
`comm` line as "explain this", not as "the port is broken". The proof, when it applies, is
`git diff fork/dev..HEAD -- <path>` being empty — C9 has five such files out of nine.

## 9. Verification

Per cluster, before merging into `boomio`:

1. **Compile on the build box:** `:app:assembleBoomioRelease` in a fresh
   `/opt/nuvio-tv-<cluster>` dir seeded with `local.properties` + the keystore. The rsync
   trap applies: a fresh git worktree has no gradle `build/` dirs, so use **no** `build`
   exclude — `--exclude='**/build'` deletes the real `com.nuvio.tv.core.build` source
   package and the build fails with `Unresolved reference 'AppFeaturePolicy'`.
   Bootstrap gotcha already learned once: `boomio` compiles `src/full/java`, so it inherits
   `full`'s flavor-scoped dependencies via `boomioImplementation extendsFrom
   fullImplementation` (`04d567bea`). If a new `fullImplementation`-style dependency is added
   for another flavour, this inheritance is what keeps `boomio` building.
2. **Unit tests** for what the cluster touches (`:app:testBoomioDebugUnitTest`). The base
   ships **12 pre-existing failures**, so a red suite is not a regression signal on its own —
   compare the failed **names**, not the count, against an untouched base extract:

   ```bash
   grep -h -B1 '<failure' app/build/test-results/testBoomioDebugUnitTest/*.xml \
     | grep '<testcase' \
     | sed -E 's/.*<testcase name="([^"]*)" classname="([^"]*)".*/\2 > \1/' | sort -u \
     > /tmp/fails-<cluster>.txt
   comm -13 /tmp/fails-base.txt /tmp/fails-<cluster>.txt   # NEW — regressions, explain each
   comm -23 /tmp/fails-base.txt /tmp/fails-<cluster>.txt   # GONE — also a regression
   ```

   Both directions must be empty. A changed **count** with an empty name diff is fine — that
   is just a cluster adding tests. The names are the signal.

   **That comparison is blind in one direction, and batch 2 proved it matters.** It can only
   flag a failure the *base* does not have, so a failure the **fork already had** reads as NEW
   even when the port is faithful. C7's `f797c7d94` (issue #12) made `fetchPersonDetail` return
   null on a null combined-credits body; a pre-existing `TmdbMetadataServiceTest` test stubs
   that call with `Response.success(null)` as incidental setup and dies at its first
   `assertNotNull`, before reaching the name fallback it is named for. That commit changes 3
   files and **no tests**, and `fork/dev` carries both the guard *and* the stale stub — **the
   fork's own suite is red on this test**, unnoticed because the fork's baseline is not clean
   either. Tell-tale: `git diff --stat boomio fork/dev -- <test>` reports **0 changes**, yet
   `fork/dev` fails it. (Contrast batch 1's C2/C13, where the same command reports **N
   deletions** because the test exists only on `boomio` — those were genuine fork bugs.)

   **So on a NEW failure, check whether `fork/dev` fails it too, before rewriting port code.**
   If it does, the port is faithful and the fix is a **port-side test repair** (C7's
   `288462e21`: re-stub with a valid empty body, plus a test for the new contract, which had
   no coverage at all) — say so explicitly in the PR so a reviewer does not read it as a port
   defect. If the fork passes it, the port broke it.
3. **On-device** for UI clusters: `.59` FireStick (armeabi-v7a) and/or `.53` RPi5. `.53`'s
   adb-over-network re-disables after each toggle — re-enable per session.
4. **Check the protocol tokens** from §4 survived after any cluster touching networking,
   auth, or sync.

C1 deserves extra care: its correctness isn't visible in the UI, so verify the request it
makes to bsf actually carries its query params (log them on the bsf side, or watch bsf's
container logs).

## 10. Working agreement

- One cluster per topic branch (`port/<cluster>`), merged or PR'd into `boomio`.
- Never merge `boomio` back into `dev`, and never port from `dev` wholesale — the point of
  the clean cut is that each cluster gets its conflicts resolved once, deliberately.
- Commit each port with the original sha in the body (`cherry-pick -x`) so provenance
  survives.
- If a cluster turns out functionally redundant with 1.0.0, drop it and record why here.

## 11. Open questions for the owner

1. `BoomioApplication` rename — port it (accepting that it rebrands the Nuvio flavor's
   internals), skip it (recommended), or restructure `main` so the class can be flavor-split?
2. **C4 (daily-show ordering):** is it functionally redundant with 1.0.0? Needs a manual
   behavioural check before porting — `git cherry` cannot see a differently-implemented fix.
3. **Which `BOOMIO_*` / `BSM_*` values belong in `local.properties` versus being entered at
   install time?** §7 lists exactly which each cluster reads, but the policy is written down
   nowhere. Today `BSM_BASE_URL` and `BOOMIO_COMPANION_URL` are set on the build box and
   `BOOMIO_BASE_URL` / `BOOMIO_MAX_RESOLUTION` are not — and landmine 13 makes that a silent
   failure rather than a build error.

Settled since the first draft:

- **C11** → net end state; skip `9f51afb64` (owner decision 2026-09-27).
- **C9** → take the net; `244f60956` supersedes `76c6caec0`.
- **C5** → **not** a net take. `cddfcc8bb` cannot apply without `7c6e2d1d6`, so the whole
  ordered ten-commit list is required (see §7).
- **C2** → partially redundant. Upstream's `78bc4635c` already fixed the stream-path manifest
  cache with a better shape, so port only `AddonRepositoryImpl` and keep upstream's
  `getStreamsFromAddon(addon, …)`.
- **The Nuvio-proper items** → nothing is incompatible with Boomio; see §4.
- **"Compile-fix-only" commits** → only the three superseded ones are skippable; the rest must
  be kept alongside their parent (landmine 5).
