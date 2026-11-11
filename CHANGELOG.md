# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **Docker detection** — `noir doctor` now reports whether Docker is installed
  and whether its daemon is running, and prints the fix when the daemon is
  stopped or refuses access. Docker stays optional: the row never changes the
  exit code, and the check only runs the local `docker --version` and
  `docker version`.
- **container awareness** — NOIR recognises when it runs inside Docker, Podman,
  Kubernetes or LXC. `doctor` shows a `container` row, and the "no device"
  guidance explains USB passthrough instead of blaming the cable.

## [0.4.2] — 2026-11-07

**Security and safety release. All users of 0.4.1 or earlier should update.**

### Fixed

- **destructive option parsing** — `--apply=false`, `--yes=false` and
  `--purge=false` are now treated as false, unknown options fail fast, and
  missing values are rejected instead of silently changing command behaviour.
- **rollback safety** — `rollback.txt` is written only by a successful apply,
  includes the device serial, preserves earlier entries, and is no longer
  erased by a later scan or dry run. AppOps failures now exit nonzero.
- **`noir block`** — no longer emits the obsolete `PROJECT_MEDIA` no-op or
  AppOps after a per-user uninstall; it writes a concrete rollback file and
  validates package names.
- **timeouts and extraction** — adb/jadx commands have real deadlines, and the
  tool downloader rejects non-HTTPS redirects and zip-slip paths.
- **scan correctness** — split APKs and `classes16.dex`+ are scanned, failed
  pulls are not cached as empty results, the endpoint cache is device-scoped,
  and stale DeepScan APKs/reports are refreshed.
- **detection** — `Runtime.getRuntime().exec` is detected, upload paths include
  stats/logs/tracking/analytics, coarse location and legacy storage are
  sensitive, domain classification no longer treats `static`/`biometrics`/
  `soundtrack` as trackers, and manifest/AXML/Dumpsys parsing handles
  permissions, defaults, legacy UIDs and malformed input.
- **reports and diff** — stable `leaksByType` keys, upload-host deduplication,
  escaped Markdown/HTML fields, schema validation, recorded scan filters and
  rollback/failure semantics.
- **CLI/UI** — global flags work before commands, `--help` no longer runs a
  scan, invalid regex/numeric options fail fast, ASCII mode covers guidance
  and progress, and 40/50-column/CJK layouts are tested.
- **release** — tagged releases run tests, build Intel and arm64 macOS
  packages, derive a valid jpackage bundle version from the tag, include
  LICENSE, and grant write permissions only to the publish job.
- **site/docs** — demo matches the current flow, English/Chinese labels are
  separated, PWA manifest is locale-aware, and README/SECURITY/contributor
  instructions match the code.

## [0.4.1] — 2026-11-06

### Added

- **automatic English interface** — NOIR now follows the Android device
  locale, then the host locale, and renders the CLI, dashboard and reports in
  English when the device is not Chinese. `--lang zh|en|auto` and `NOIR_LANG`
  override the detection. Reports keep stable `categoryKey` / `actionKey`
  fields so a Chinese report can still be diffed against an English one.

### Fixed

- **screen-capture recommendations** — removed the misleading
  `PROJECT_MEDIA ignore` suggestion. Capability alone is not an action, and
  that app-op only controls a permanent grant. AppOp plans now query the
  current effective mode and omit actions that would be no-ops.

## [0.4.0] — 2026-11-06

### Added

- **offline HTML report** — every scan now writes a self-contained
  `report.html` with environment details, category exposure, top findings,
  recommendations, searchable app cards and shared-UID groups. No external
  assets, fonts or network calls.

## [0.3.0] — 2026-11-06

### Added

- **`noir diff`** — compare two `report.json` files and report added, removed
  and changed apps, including score deltas, permissions and endpoints.
  Supports `--json` and `--fail-on-regression` for CI.

## [0.2.7] — 2026-11-06

### Fixed

- **legacy Windows console** — ANSI colour is no longer emitted when stdout is
  a classic `cmd.exe`/conhost window. This prevents raw `←[38;2;…m` sequences
  from flooding the dashboard; `--color` still forces colour, and Windows
  Terminal / VS Code / ANSI-enabled shells are detected automatically.

## [0.2.6] — 2026-11-04

### Fixed

- **AppOps diagnostics** — `applied.txt` now keeps each command's full adb
  stdout/stderr, exit code and timeout state; failures are summarized in the
  terminal. Reports now include Android release/SDK, build id, fingerprint,
  current user, host and tool version.
- **rollback accuracy** — when an AppOp change succeeds, the previous mode is
  queried and written to `rollback.txt` instead of always assuming `default`.
- **Windows launcher** — the generated `noir.bat` switches the console to
  UTF-8 and passes UTF-8 JVM encodings, so box-drawing and CJK output render
  correctly from `installDist`.

## [0.2.5] — 2026-11-02

### Fixed

- **vendor attribution** — the package name is now authoritative, and host
  fallback scores vendors by suffix instead of "first substring wins".
  Infrastructure hosts (`schemas.android.com`, `github.com`, …) are ignored, and
  OPPO / vivo / Huawei / Samsung / Meituan / Pinduoduo / JD / Bilibili / Kuaishou /
  Weibo were added. Fixes hundreds of apps being labelled Google. Reported in [#12].
- **domain export** — split into `domains-trackers.txt` (safe to block),
  `domains-other.txt` (review) and `domains-functional.txt` (push/login/update/
  payment/store/cloud; do not block). Bare IPs, `localhost` and test/staging
  hosts are dropped; `domains.txt` now contains only the tracker tier.
  Reported in [#11].

[#11]: https://github.com/Verlintas/NOIR/issues/11
[#12]: https://github.com/Verlintas/NOIR/issues/12

## [0.2.4] — 2026-11-01

### Changed

- scan progress is now streamed from the device and redrawn **once per APK**
  (spinner + ETA), instead of once per batch of eight

### Docs

- dashboard screenshot (`docs/screenshot.png`) in the README

## [0.2.3] — 2026-11-01

### Fixed

- `--apply` in a non-interactive run (piped stdin, CI) now refuses to execute
  unless `--yes` is also passed. `deepscan --apply` previously skipped the
  confirmation because it only prompted on a TTY.

## [0.2.2] — 2026-11-01

### Changed

- build: Gradle wrapper 9.8.2, Kotlin 2.4.21 (both plugins pinned to the same version)
- CI: `actions/checkout@v7`, `actions/setup-java@v6`, `actions/upload-artifact@v7`,
  `actions/download-artifact@v8`, `softprops/action-gh-release@v3`

No user-facing behaviour changed in this release; it exists to validate the
upgraded release pipeline.

## [0.2.1] — 2026-11-01

### Fixed

- **dumpsys runtime permissions** — lines ending in `, flags=[…]` or `; flags=[…]`
  were dropped, so apps holding granted location / camera / microphone never
  entered the candidate list. Reported in [#9].
- **device-side extraction** — `unzip -p apk 'classes*.dex'` is not portable; some
  vendor builds (Huawei/HarmonyOS) do not expand entry wildcards and return
  nothing with exit code 0. The scanner now names `classes.dex … classes15.dex`
  explicitly, and pulls the APK for host-side parsing when nothing is extractable.
  Reported in [#10].
- **cache invalidation** — endpoint cache is versioned (`endpoints-v2.tsv`) so
  results produced by the broken extraction are not reused.
- **Windows portable** — `Start NOIR.bat` now runs the bundled
  `runtime\bin\java.exe` directly with UTF-8 console settings, and jpackage is
  built with `--win-console`; the release workflow smoke-tests `NOIR.exe --version`
  on Windows, `NOIR` on Linux and the `.app` binary on macOS.

[#9]: https://github.com/Verlintas/NOIR/issues/9
[#10]: https://github.com/Verlintas/NOIR/issues/10

## [0.2.0] — 2026-11-01

### Added

- manifest-level audit via an in-house AXML parser (no external dependency):
  exported components without a permission, `usesCleartextTraffic`, `allowBackup`,
  `debuggable`, `targetSdk`, `networkSecurityConfig`
- data-type correlation: each permission is mapped to the data it exposes
  (location / contacts / SMS / calls / mic / camera / screen / apps / identity /
  files / calendar / accounts) and joined with upload hosts — `leaks` in the report
- shared-UID section: lists packages that share one Linux uid and therefore hold
  the union of each other's permissions
- SDK / tracker fingerprinting from domains and decompiled class paths
  (Umeng, Firebase, AppsFlyer, Adjust, Meta, ByteDance, Tencent, Alibaba, Baidu,
  Sogou, iFlytek, Sentry, Mixpanel, OneSignal, Bugly, Getui, JPush, MiPush …)
- deeper code signals: `SystemProperties.set`, SMS APIs, device-id getters,
  WebView bridges, accessibility / notification-listener / device-admin services
- deepscan risk now includes manifest exposure (exported services/receivers/providers,
  cleartext + upload endpoint, backup, debuggable)

## [0.1.2] — 2026-11-01

### Fixed

- deepscan: skip decompiled library code (androidx / kotlin / okhttp / gson …) when
  counting signals — the filter used paths relative to the output root instead of
  `sources/`, so `Class.forName` in androidX was inflating `reflection`
- deepscan: surface a `jadx exit != 0` as an error instead of silently scoring an
  empty output directory

## [0.1.1] — 2026-11-01

### Added

- `noir deepscan` — pull + decompile the riskiest apps in parallel and score
  code-level signals (`exec`, dynamic loading, app disabling, accessibility
  rewriting, screen capture, reflection, crypto, networking)
- `--apply` / `--purge` cleanup flow after DeepScan, with per-app confirmation
- decompilation cache keyed by package + APK path
- portable, double-clickable builds with a bundled runtime (jpackage):
  `Start NOIR.command` / `Start NOIR.bat` / `Start-NOIR.sh`

### Changed

- multiple connected devices: a physical device is preferred over an emulator;
  interactive runs ask which one to use

## [0.1.0] — 2026-11-01

### Added

- `noir scan` — inventory every app with INTERNET + sensitive permissions
- endpoint extraction from APKs on-device (`unzip | strings`), with a pull-based fallback
- classifier (telemetry / ads / security / AI / location / IME / carrier …) and vendor attribution
- risk scoring based on permissions, endpoints and system/privileged status
- `noir ui` — adaptive monospace dashboard (CJK-aware width, ANSI gradient, `--ascii` fallback)
- `noir auto` — plan + `--apply` for reversible app-ops, plus `plan.txt` / `rollback.txt`
- `noir deep <pkg>` — pull one APK, run jadx, grep for red-flag calls
- `noir block <pkg…>` — print or apply neutralisation commands with restore hints
- `noir setup` — download `adb` / `jadx` into `~/.noir/tools` when missing
- `noir doctor` — environment check
- guided first run when invoked without arguments
- device onboarding: developer-mode / USB-debugging instructions, 180 s wait loop,
  dedicated messaging for `unauthorized`
- `noir --about`, `noir --version`
- shared-UID aware `dumpsys package` parser (system-uid permissions live in a separate section)
- endpoint cache keyed by package + APK path
- cross-platform CI on Ubuntu, macOS and Windows
- release workflow producing SHA-256 checksum files alongside zip archives per OS

### Known limits

- static analysis only: it proves capability and endpoints, not actual traffic
- app-ops cannot revoke `QUERY_ALL_PACKAGES` or device identifiers
- DNS-level blocking is exported as `domains.txt`, not applied
