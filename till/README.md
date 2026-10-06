# The till

One Kotlin Multiplatform codebase for the shop's till (CR-30-1, accepted 28 Sep 2026): Android is the primary target, and a desktop JVM application runs the same code on Windows 10/11 64-bit and Linux 64-bit. This folder is its own Gradle build; the backend never depends on it, and it talks to central only through the sync contract (`backend/app/src/main/resources/openapi/sync.yaml`, doc 32).

The desktop trial is the first step CR-30-1 names: sell, print and close the day on a PC before the till screens are built for real. What it does, how to run it and what it measured are below.

## Modules

| Module | Source sets | What it holds |
|---|---|---|
| `core` | commonMain | The business truth, with no I/O: the sale and basket, receipt numbering from the till position's series, the session and blind close, the Z-report, the snapshot (verification, staging, held rows), the facts and the outbox, the content hash of a receipt bundle (the kernel's rule), the receipt and Z-report layouts in three languages. `TillService` is the use cases. Everything outside is a port in `core/port`: `TillStore`, `Central`, `SignatureVerifier`, `PinVerifier`, `TillClock`, `KeyVault`. |
| `sync` | commonMain, jvmMain | `HttpCentral`: the sync contract over Ktor (enrol, device token, snapshot, batches, heartbeat). jvmMain: Ed25519 with the JDK, Argon2id PIN check with BouncyCastle. |
| `db` | commonMain, jvmMain | The SQLDelight schema (`Till.sq`) and `SqlTillStore`. jvmMain: `EncryptedJvmDatabase` (sqlite-jdbc-crypt, SQLCipher 4 format). |
| `peripherals` | commonMain | `PrinterPort`, the ESC/POS encoder (raster `GS v 0` in bands, cut, drawer kick), `ScanReader` (keyboard-wedge scanner), simulators. |
| `peripherals-jvm` | JVM | `NetworkPrinter` (TCP 9100) and `PreviewFolderPrinter` (a PNG and the raw `.bin` per slip). |
| `render` | commonMain, jvmMain | `ReceiptRasteriser`. jvmMain: `SkiaReceiptRasteriser`, Skia's paragraph engine (HarfBuzz shaping) with the bundled Noto Sans, Noto Sans Sinhala and Noto Sans Tamil (`render/src/jvmMain/resources/fonts`, SIL OFL 1.1), thresholded to 1 bit. |
| `ui` | commonMain | The shared Compose Multiplatform screens (`TillApp`: enrol, sign in, open session, sell, cash, close, Z-report), `TillController` that drives them, `TillTheme` from the back office's tokens, `HelloScreen` for the Android app. |
| `app` | Android | The Android application. It shows the shared `HelloScreen` for now (see "What the Android side needs next"). |
| `desktop` | JVM | The Compose Desktop window, the wiring (`DesktopTill`), the key vaults (DPAPI on Windows, a mode-600 file on Linux), packaging, and the `devEnrolmentCode` helper. |

A platform difference is an interface in common code with one implementation per platform, never a branch in business code (research report 7A.1).

## Build and test

JDK 21 is the only requirement for the desktop. The Android modules and targets build only where an Android SDK is installed (`ANDROID_HOME`, or `sdk.dir` in `local.properties`); `-Ptill.android=true|false` overrides the guess, so a desktop-only PC builds without one.

```
./gradlew :core:jvmTest :sync:jvmTest :db:jvmTest :peripherals:jvmTest :peripherals-jvm:test :render:jvmTest :desktop:test
./gradlew :app:assembleDebug          # with an Android SDK
./gradlew :desktop:run                # the desktop till, from the IDE's terminal
```

The receipt golden images live in `render/src/jvmTest/resources/golden` (a Sinhala receipt, a Tamil receipt, a Sinhala Z-report). The test writes what it rendered to `render/build/golden-actual`; when a layout changes on purpose, write the goldens again with `-Pgolden.update=true` and review the PNGs in the diff. Windows (DirectWrite) and Linux (FreeType) draw glyph edges slightly differently, so the comparison allows a small share of differing dots.

`desktop/src/test/.../TillScreensTest` drives the shared screens headless with the keyboard (enrol, sign in, open, scan two items with Enter, F2 and Enter for cash, close with the blind count, Z-report) against the real encrypted database and a stand-in central that signs its snapshot with a real Ed25519 key. Screenshots of each step go to `desktop/build/ui-screens`.

On this machine, set the temp and Gradle folders on D: first (C: is nearly full):

```
export GRADLE_USER_HOME=D:/gradle-home TMP=D:/tmp/javatmp TEMP=D:/tmp/javatmp
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=D:/tmp -Djava.io.tmpdir=D:/tmp/javatmp"
```

## Running the desktop till against the local stack

Needs `make up` and `make demo-data`. The till enrols like any till: the shop's administrator registers the device, puts it on a till position and issues a one-time code; the till types the device id and the code once.

1. **Issue the code.** The back office has no screen for enrolment codes yet, so use the helper, which calls the same API as the backend's demo till (`make demo-till-sale`):

   ```
   export COOP_TILL_HOME=D:/tmp/coop-till-trial     # optional; the till's data folder
   ./gradlew :desktop:devEnrolmentCode
   ```

   As `m101-manager` it registers `DESKTOP-TRIAL-S01` at Kuliyapitiya town shop (only the first time), puts it on till position 2 (position 1 is the demo till's), and issues a code. It prints the device id and the code, fills the till's enrol form (`enrol-prefill.properties` in the data folder), and writes the trial's price book (`prices.csv`; see "Deviations"). Run it again for a new code; the device is reused. By hand, the three calls are `POST /v1/party/devices`, `POST /v1/party/devices/{id}/assign` and `POST /v1/sync/devices/{id}/enrolment-codes`, as the shop's manager.

2. **Start the till** with the same `COOP_TILL_HOME`: `./gradlew :desktop:run`, or the packaged `coop-till` (below).

3. **Enrol.** The form is filled in; press *Enrol and take the snapshot*. The till enrols (its credential, its next device sequence, its receipt series, central's snapshot key), downloads the snapshot and verifies it: the Ed25519 signature over the manifest, the manifest against the answer, and the SHA-256 of every table. A snapshot that fails is refused and the till keeps the one it has.

4. **Sign in.** An operator with a PIN in the snapshot signs in with the PIN (Argon2id, checked on the till; five wrong PINs in a row at the till lock it for fifteen minutes, kept in the database across restarts and sent to central as a `TILL_PIN_LOCKOUT` audit fact). The demo stack has no till users with a PIN yet, so the screen offers *Continue as trial cashier*, but only because `devEnrolmentCode` wrote `trial_cashier=true` into the prefill file (see "Deviations").

5. **Sell.** Open the session with a float. The scan field always has the focus: a keyboard-mode scanner types the barcode and Enter; a name typed there searches. F2 takes cash, Enter completes, the receipt is numbered from the till position's series (`M101-S01-T2-RCT-n`), stored, printed, and the drawer kick is sent. F10 closes the session: the cashier keys the counted cash without seeing the expected figure, and the Z-report prints.

6. **Offline.** Every fact is written to the encrypted database first (`synchronous=FULL`, so a power cut does not lose a printed receipt), in one transaction with the counters that number it, then uploaded gzip-compressed by a background loop every 20 seconds (and after each sale). With central unreachable the bar says *Offline* and selling goes on; the outbox drains in order when central is back, a retried batch keeps its batch id.

7. **What the till trusts and keeps** (wave 2, `docs/progress/deviations/2026-10-06-wave2-till-trust-and-durability.md`; CR-32-1 items 3 and 4):
   - The server address must be `https://`, except on this PC (`localhost`, `127.0.0.1`, `[::1]`, `*.localhost`); the enrolment answer's identity provider must be on the same host unless `COOP_TILL_TOKEN_ENDPOINT` is set. A till enrolled over plain http before this rule shows a banner and must be enrolled again with a new code (a trial database can also just be deleted). Central's signing key is trusted on first use.
   - A snapshot is applied only when it is signed with the enrolled key (`key_id`), is not older than the version held, and, for a delta, starts at exactly that version; otherwise the till asks for a full snapshot (`since=0`). After `pos.snapshot_staleness_days` (default 3) without a verified answer the supervisor sees a banner.
   - Acknowledged facts stay in the outbox for `sync.outbox.retention_days` (default 7), so central's `RESEND_FROM` and `409 sync.sequence_gap` are answered by sending them again. A QUARANTINED outcome is a *problem for the office* (the status bar button; the Z-report prints "N facts refused by central, see the office"); the receipt stands as issued. `FLOOR_NOTICE` is a banner.
   - A revoke (403) is honoured only when it is signed with the enrolled key, names this device and was issued after the enrolment: the till locks, deletes its snapshot rows and keeps its settings, receipts, sessions and outbox. When the office reinstates the device, or the till enrols again with a new code, it uploads its kept outbox from the last acknowledged sequence.
   - The clock offset is taken from every ack and heartbeat and dropped (a problem is recorded) when the PC's clock is moved by hand; an offset over 7 days is refused. The business date never opens more than a day after central's last `server_time` when the till synced in the last 48 hours; a supervisor (`pos.session.manage` and their PIN) may move it back while no session is open.
   - At start the receipt and device-sequence counters are moved above any number already stored (a problem is recorded). The database key file is written atomically; a missing key beside an existing `till.db`, or a damaged key, stops the till with "the office must re-enrol this PC" instead of making a new key.

Settings, as environment variables:

| Variable | Meaning | Default |
|---|---|---|
| `COOP_TILL_HOME` | The data folder: database, key, price book, print previews, `startup.log` | `%LOCALAPPDATA%\CoopTill`; `~/.local/share/coop-till` |
| `COOP_TILL_PRINTER` | `tcp://host:9100` for a network ESC/POS printer | empty: the preview folder |
| `COOP_TILL_PAPER` | `80` (576 dots) or `58` (384 dots) | 80 |
| `COOP_TILL_TOKEN_ENDPOINT` | The identity provider's token URL, when the till reaches it by another address than the enrolment answer gives | the answer's |
| `COOP_TILL_CA_FILE` | A PEM file of a certificate authority to trust besides the system's, for a server with a private certificate (never a "trust all") | none |
| `COOP_TILL_TRIAL_CASHIER` | `true` offers the trial's stand-in cashier while the shop has no operator; refused once a snapshot has carried an operator | off; `trial_cashier` in `enrol-prefill.properties` |
| `COOP_TILL_SERVER`, `COOP_TILL_DEVICE_ID`, `COOP_TILL_CODE`, `COOP_TILL_SERIAL` | Fill the enrol form | from `enrol-prefill.properties` |
| `COOP_TILL_MEASURE` | Close the till this many seconds after the first frame and log start-up time and memory | off |

With no printer, each slip lands in `<COOP_TILL_HOME>/print-preview` as a PNG (what the paper shows) and a `.bin` (the exact bytes a printer would get: `ESC @`, the raster in 128-row bands, feed, cut, drawer kick). `cat file.bin > /dev/usb/lp0`, or `nc printer 9100 < file.bin`, prints it.

**Opt-in test against the stack** (never in CI by default): after `devEnrolmentCode`, `./gradlew :desktop:test -Ptill.stack=true --tests '*DesktopStackTest*'` with the same `COOP_TILL_HOME` enrols if needed, verifies the snapshot, sells two items, closes the session, prints to the preview folder, uploads, and checks that central holds the receipt with no flag the till caused.

## Packaging

| Task | Where it must run | Output |
|---|---|---|
| `./gradlew :desktop:createDistributable` | the OS it is for | `desktop/build/compose/binaries/main/app/coop-till/`: the launcher, the app and its own trimmed Java 21 runtime |
| `./gradlew :desktop:packageMsi` | Windows | `desktop/build/compose/binaries/main/msi/coop-till-<version>.msi`, a per-user install (no administrator) |
| `./gradlew :desktop:packageDeb` | Linux | `desktop/build/compose/binaries/main/deb/coop-till_<version>_amd64.deb` |

jpackage cannot cross-build: CI builds the Linux distributable on its Ubuntu runner (job *Till tests and APK*, artefact `till-desktop-linux`); there is no Windows runner yet, so the MSI is built on a Windows PC. The version is `till.version` in `gradle.properties`, one number for every target. Signing (Authenticode, GPG, our release key) and the self-updating launcher of CR-30-1 point 4 are not built.

## Measured on the development PC (29 Sep 2026)

Intel Core i5-1245U, 16 GB, Windows 11 Pro, with the CPU near 100% from other work (Docker, IDE sessions) during the runs, so these are pessimistic.

| What | Result |
|---|---|
| Start to first frame (packaged app, warm disk cache) | 4.7–5.1 s typical, 6–8 s on the first runs after a build; JVM to `main()` 0.35–0.5 s, key vault (DPAPI via JNA) 0.6 s, encrypted database 0.45 s, Compose and Skia to the first frame about 2.4 s |
| Memory after start (sign-in screen) | Java heap 26–30 MB used of 43 MB committed; working set of the process about 260–275 MB, private bytes 240–255 MB |
| Distributable folder | 159 MB (78 MB runtime, 80 MB app: sqlite-jdbc with every OS's native library 16 MB, Skia 14 MB, ICU data 10 MB, BouncyCastle 8 MB, Compose) |
| MSI | 94 MB |

What would make it smaller and faster, not done in the trial: strip the other OSes' natives from sqlite-jdbc (about 15 MB), replace BouncyCastle by a small Argon2 (about 8 MB), ProGuard the release build, and a class-data-sharing archive (the jlinked runtime needs `--generate-cds-archive` for `-XX:+AutoCreateSharedArchive` to work).

## Deviations from the research report and the design

- **Prices.** Central's snapshot carries no price table yet (doc 32 section 5.1 lists prices; the M3 snapshot contributor is not built). The till reads a table `price` (`sku_id`, `unit_price`) when central sends one, then a local price book (`prices.csv`, by SKU id or barcode, which `devEnrolmentCode` writes from the society's published shelf list or, when that cannot be read, the demo catalogue's printed MRPs), and otherwise asks the cashier to key the price.
- **Trial cashier.** The demo stack has no till user with a PIN at the shop, so the snapshot's operator table is empty; a till that opted in (`COOP_TILL_TRIAL_CASHIER=true`, which `devEnrolmentCode` writes as `trial_cashier=true` into `enrol-prefill.properties`) then offers a cashier with a fixed id per till, as the backend's till simulator does. It is off by default and refused for good once a snapshot has carried an operator (TWK-04); no field in the sync contract, no second build. On such a trial till with no operators, the trial cashier may also move the business date back. The PIN path (Argon2id, lockout) is built and tested but not exercised against the stack.
- **FLAGGED outcomes** (doc 32 section 3.4) have no channel to the till: central's `BatchIngestor` says a business-rule flag reaches the device later through what the module publishes, and nothing does yet. The back office sees flags; the till records only QUARANTINED outcomes.
- **Configuration from central.** The kernel has no snapshot contributor for the location's configuration items yet (doc 32 section 5.1). The till reads a table `config` (row: `key`, `value`) when central sends one (`sync.outbox.retention_days`, `pos.snapshot_staleness_days`) and keeps its defaults (7, 3) until then.
- **Who may move the business date back.** No till permission for it exists yet; the till uses `pos.session.manage`, the shop-in-charge's session permission of 21A (offline-allowed permissions travel with the operator in the snapshot).
- **Clock jumps** are measured against `System.nanoTime` while the till runs; across a restart only a backward move of the PC's clock is detected, until the next heartbeat measures the offset again.
- **No `security` module.** CR-30-1 lists one; the trial keeps the `KeyVault` port in core and the desktop vaults in `desktop`. The Ed25519 and Argon2id checks are in `sync`'s jvmMain.
- **Database key used raw.** The key is 256 random bits from the key vault, so SQLCipher's PBKDF2 stretching is skipped (it cost about two seconds per start). SQLCipher for Android takes the same raw key.
- **The native SQLite library** is copied once into the data folder (`native/<version>`) instead of being unpacked to the temp folder at every start.
- **Text on screen** uses the OS's fonts (Windows has Nirmala UI for Sinhala and Tamil); the bundled Noto fonts are used for the paper only. A Linux PC needs `fonts-noto-core` until the screens load the bundled fonts too.
- **Room is removed** from the Android app (it held only the hello scaffolding); the till's database is SQLDelight in `db`.
- **Versions** are newer than the research report's table: Kotlin 2.3.21, Compose Multiplatform 1.11.1 (material3 1.9.0), AGP 8.13.2 with Gradle 8.14.4, SQLDelight 2.2.1, Ktor 3.4.3, sqlite-jdbc-crypt 3.53.2.0. All in `gradle/libs.versions.toml`.
- **Hydraulic Conveyor** was not evaluated; the trial packages with the Compose Gradle plugin's jpackage tasks.
- **The Sinhala and Tamil wording** on the paper (`ReceiptMessages`) is a first draft and wants a translator's review before a pilot.

## What the Android side needs next

1. A `SqlDriver` for Android in `db` (`AndroidSqliteDriver` with SQLCipher for Android's `SupportOpenHelperFactory`, the same raw key), and an Android Keystore `KeyVault`.
2. Ed25519 and Argon2id for Android: BouncyCastle works there too (move `JvmCrypto.kt` to a shared JVM-and-Android source set), or Conscrypt/Tink.
3. An Android `ReceiptRasteriser` (StaticLayout on a Canvas with the same Noto fonts) with its own golden images, and the printer transports in `peripherals-android` (USB host, Bluetooth SPP, TCP 9100, the Sunmi SDK).
4. Wire `MainActivity` to `TillApp` and `TillController` as `desktop/Main.kt` does, with WorkManager for the upload, and lock-task kiosk mode.
5. Window-size classes in `ui` for the compact and medium layouts (research report 7A.2); the trial's layout is the expanded one.
