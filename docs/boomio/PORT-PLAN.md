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

## 7. Landmines

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
5. **Compile-fix-only commits — meaningless standalone:** `c269cd8ec` (C1), `3ae3acdc9` +
   `5e7b7a699` (C2), `b51e0611b` + `04ee7e933` (C5), `99278865d` (C7), `ba00e8a64` (C10),
   `f86b60a2c` (test-only, C1). Port their parents, or the fix is a no-op / won't apply.
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

## 8. Verification

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
2. **Unit tests** for what the cluster touches (`:app:testBoomioDebugUnitTest`).
3. **On-device** for UI clusters: `.59` FireStick (armeabi-v7a) and/or `.53` RPi5. `.53`'s
   adb-over-network re-disables after each toggle — re-enable per session.
4. **Check the protocol tokens** from §4 survived after any cluster touching networking,
   auth, or sync.

C1 deserves extra care: its correctness isn't visible in the UI, so verify the request it
makes to bsf actually carries its query params (log them on the bsf side, or watch bsf's
container logs).

## 9. Working agreement

- One cluster per topic branch (`port/<cluster>`), merged or PR'd into `boomio`.
- Never merge `boomio` back into `dev`, and never port from `dev` wholesale — the point of
  the clean cut is that each cluster gets its conflicts resolved once, deliberately.
- Commit each port with the original sha in the body (`cherry-pick -x`) so provenance
  survives.
- If a cluster turns out functionally redundant with 1.0.0, drop it and record why here.

## 10. Open questions for the owner

1. `BoomioApplication` rename — port it (accepting that it rebrands the Nuvio flavor's
   internals), skip it (recommended), or restructure `main` so the class can be
   flavor-split?
2. **C4 (daily-show ordering):** is it functionally redundant with 1.0.0? Needs a manual
   behavioural check before porting — `git cherry` can't see it.
3. ~~**C11:** port the whole history (churn) or only the net end state (long-press Like)?~~
   **Resolved 2026-09-27:** net end state — skip `9f51afb64`, port `b884beb52`'s long-press
   Like and its follow-ups.
5. **C5:** `cddfcc8bb` deletes `HB/{HubModels,HubScreen,HubViewModel}.kt` that `7c6e2d1d6`
   created, so the cluster must be taken as a net too — does anything *between* those two
   commits in `fork/dev` depend on the deleted files (i.e. must an intermediate commit be
   ported for the tree to compile)?
4. Which `BOOMIO_*` build-time values belong in `local.properties` versus being entered at
   install time? Documented only in code today.
