# Bygdok Eternal

Bygdok Eternal is an Android-hosted, single-player World of Warcraft: Wrath of the Lich King (3.3.5a) realm built on AzerothCore. It brings the realm server and its management into one Android app, with Playerbots and gameplay modules designed to make Azeroth feel active even when you play alone.

This repository is a fork of [`duall/singlePlayerWow-android`](https://github.com/duall/singlePlayerWow-android). Its Android-hosted server foundation builds on [`Pocket Realm`](https://github.com/puzzled-pancake/pocket-realm), adapted here for AzerothCore and WoW 3.3.5a and integrated with Bygdok Eternal's gameplay modules and configuration. The Android app replaces the inherited Termux-based server setup; users do not need to install Termux or Pocket Realm separately.

This README is the central project overview and local development guide.

## What it includes

- **AzerothCore server:** An Android ARM64 build of the pinned AzerothCore fork, including `authserver` and `worldserver`.
- **Embedded realm database:** MariaDB is packaged with the app runtime; the app is designed to initialize and manage it locally.
- **Android management app:** A Kotlin/Jetpack Compose app with initial setup, server start/stop and status, activity logs and console, account management, network settings, mod controls and configuration, and realm backup import.
- **Solo-play features:** Playerbots and a curated set of AzerothCore modules for progression, quality of life, and gameplay customization.
- **Bygdok modules:** Guild Mate for autonomous guild characters, Ollama Chat for optional LLM-powered bot dialogue, and Transmog Plus for slot-based transmogrification.
- **Separate game data:** Maps, vmaps, mmaps, and dbc data are downloaded on first run; they are not included in the APK.

The Android app hosts the realm server; it is not the WoW game client. You need a compatible 3.3.5a client to connect to the realm.

## Project status

The Android app and embedded-runtime build and orchestration are under active development. The repository includes CI workflows for the Android ARM64 MariaDB and AzerothCore runtimes and for packaging those runtimes into a debug APK.

The full local `npm run build` pipeline has completed successfully, producing a debug APK with the rebuilt native runtime. The Android JVM suite covers module configuration, persistence, dependency handling, bot-account management and database-client diagnostics. The five native sources changed for mod switches have also passed Android cross-compiler syntax checks.

These checks do **not** establish end-to-end operation on a phone. The runtime and new mod controls still need end-to-end validation on a physical device. The Samsung Galaxy S25 is the documented integration-test device. Treat the runtime as experimental until setup, startup, gameplay and persistence have been confirmed there.

## Getting started

### Get a build

From the repository root in the Windows VS Code terminal, run:

```powershell
npm run build
```

No `npm install` is needed: npm is only the entry point, with no JavaScript package dependencies. The command stages the pinned Pocket Realm MariaDB provider, builds native dependencies and AzerothCore under WSL using the local modules, then runs the Windows Gradle wrapper to assemble the APK. It stops on the first failure.

The output is [`android/app/build/outputs/apk/debug/app-debug.apk`](android/app/build/outputs/apk/debug/app-debug.apk), for ARM64 Android 11 (API 30) or newer. The command builds the APK only; it does **not** install or update the app on your phone.

Prerequisites:

- Node.js 18 or newer, Python 3, and Java 17 on the Windows PATH.
- An Android SDK configured for the Gradle project (through [`android/local.properties`](android/local.properties) or the standard Android SDK environment variables), with Android platform/build tools 35.
- A working default WSL Linux distribution with Python 3.9+, CMake, Ninja, Git, Make, Perl, curl, unzip, tar, GCC/G++, and pkg-config.
- The **Linux** Android NDK `29.0.14206865` installed under `~/android-sdk/ndk/` in WSL. Alternatively, make `ANDROID_NDK_ROOT` available in the WSL environment. The Windows NDK is not interchangeable with it.

To check the command's Python, Java, and WSL native prerequisites without downloading or building:

```powershell
npm run build -- --check
```

The first full build can take considerable time and downloads the database provider, AzerothCore, native dependency sources, and Gradle dependencies as needed; it does not download modules. Native build files are cached under `~/.cache/bygdok-eternal/` in WSL, with two parallel jobs by default. Packaged runtime output is written back into `runtime/build/` for the APK build. Subsequent builds reuse dependencies and rebuild the server with current local module sources. Android SDK/package availability is checked by Gradle during the actual APK build.

Provider staging retries temporary Windows access/sharing errors when replacing its generated runtime directory. If access remains denied, the build stops with the affected path: close programs or terminals using that directory, then retry. Cleanup failures are reported rather than ignored.

The GitHub Actions Android and integration workflows remain separate build options.

### Install or update the app

Install the debug APK on your device after building. To retain existing app data, install it as an update with the same application ID and signing key rather than uninstalling the previous version.

With Android SDK Platform Tools installed and USB debugging enabled, install/update the existing APK from the repository root with:

```powershell
npm run deploy
```

This does not rebuild or launch the app. It finds ADB through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, [`android/local.properties`](android/local.properties), or your PATH, checks for a connected and authorized device, then runs `adb install -r` to preserve existing app data. It never uninstalls or clears data. Open Bygdok Eternal on the phone after installation.

Use `npm run deploy -- --check` to check the APK and device without installing. If multiple devices are connected, select one with `npm run deploy -- --serial <device-id>` (find IDs with `adb devices`).

If an update is rejected because of a signing-key mismatch, uninstalling would erase app-private data; do not uninstall merely to bypass that error if you need to keep your realm.

### First launch

The app's initial setup flow is designed to:

1. Initialize the embedded database.
2. Collect the realm name and network addresses.
3. Create the initial game-master account.
4. Save the realm configuration.

After setup, start the realm from **Home**. The first server start downloads and extracts the required WoW server data (maps, vmaps, mmaps and dbc). Allow substantial free storage and a reliable internet connection. Once downloaded, this data is kept in the app's private storage and reused.

Connect using a compatible WoW 3.3.5a client configured with the realm address set in the app. Use the account created during setup. The game client is separate; this project does not require a particular client-running solution such as Winlator. Network access from another device depends on the address and network configuration; remote connectivity is not guaranteed by this README.

The dashboard also provides **Accounts**, **Network**, **Console**, **Import** and **Mods** tabs. Use Import when restoring a supported realm backup rather than repeating first-time setup.

### Managing mods

Open **Mods** in the dashboard while the server is stopped. All 15 bundled modules have an enable switch and **Configure** screen. Search for a configuration key, edit its value using the existing format (including quotes around strings), and select **Save settings**. Changes apply at the next server start; they are not live reloads. Unsaved editor changes are retained when switching tabs, and starting from Home returns to Mods until you save or discard them.

Settings are stored separately from the APK's templates in the app's private `files/mod-settings` directory. They survive rebuilding and installing an update with the **same application ID and signing key**, and are reapplied whenever effective server configs are generated. New bundled settings are merged in without overwriting saved values. Uninstalling, clearing app data, or switching to a separately installed application/signature does not preserve this storage. Module preferences are not a substitute for a realm backup. App-saved settings take priority over portable backup configs during import validation.

Guild Mate, Bot Level Brackets, and Ollama Chat are automatically held inactive when Playerbots is off, without erasing their own preferences. Auction House Bot and Duel Reset master switches disable both associated features, while retaining individual buyer/seller or cooldown/health settings for re-enabling.

Switches disable runtime behavior, not compiled module code or SQL migrations. Existing auctions, houses, stored reagents, progression data and appearances are not removed. In particular, disabling Individual Progression does not revert its world SQL. Ollama Chat still needs a separately configured Ollama endpoint and model.

Emblem Transfer, Guildhouse and Reagent Bank now have working master switches, and NPC Enchanter suppresses its announcement when disabled. Run the full `npm run build` to compile those native module changes; assembling only the Android UI against old native binaries cannot add these server-side controls.

### Bot accounts and Guild Mate setup

You can configure existing accounts and guilds without starting authserver or worldserver or logging into the game:

1. Stop the realm, then open **Accounts → Bot links / Guild Mate exclusions**.
2. Select **Load / refresh database**. The app starts only MariaDB if needed.
3. Select your main account, search for other accounts or characters, then enable/disable their account links. Confirm each change. Links grant trusted Playerbots access **in both directions**, matching the in-game linking command; this is a local realm-administrator action and does not require the in-game security key.
4. Enable **Exclude from Guild Mate** for each human account. Disable it only for accounts whose characters should be eligible for autonomous login. Exclusions and account links are separate settings.
5. Open **Mods → Guild Mate → Configure**, load the guild list, select the existing guilds to use and choose **Save settings**.

Account links and exclusions are saved immediately in the realm database and verified by reading them back. Guild selections use the persistent mod-settings store and apply at the next realm start. Neither action creates accounts, creates guilds or adds characters to guilds; characters must already belong to a selected guild. Character lists show current guild membership, not a guarantee of bot eligibility. Guild Mate still respects random-bot filtering, account exclusions and its other configuration.

Trusted linked-account control requires `AiPlayerbot.AllowTrustedAccountBots` to be enabled in Playerbots configuration. Linking is not required merely for Guild Mate to auto-login eligible members of a configured guild.

Controls are locked while the game servers are active or startup/import is in progress. MariaDB stays available after database management; use **Home → Stop database** when finished, or start the realm normally. Missing schemas and database errors are shown rather than treating a failed load as an empty list.

The local MariaDB client may warn that `--ssl-verify-server-cert` is disabled for its passwordless root connection. Administrative queries currently connect to the embedded database on `127.0.0.1`; this warning does not by itself mean the query failed. Client diagnostics are kept in Console logs, separate from database result rows. Actual query failures still stop the operation and display an error.

## Solo-play modules

- **Playerbots** provide autonomous characters and are a core part of the solo-play experience.
- **Guild Mate** can automatically run configured guild characters using Playerbots AI. Human accounts can be excluded.
- **Ollama Chat** can add contextual, personality-driven bot dialogue through an Ollama API. It is optional and may consume significant device or remote-host resources.
- **Transmog Plus** provides slot-based appearances and an account-wide collection. Its visual client addon is optional; the module also has an in-game gossip interface.

All 15 selected modules are stored as ordinary source files in [`modules/`](modules/), not Git submodules. Builds use these local copies and do not download modules or check out upstream versions over local edits. [`modules/manifest.json`](modules/manifest.json) records the selected modules and their upstream provenance:

- `mod-account-achievements`
- `mod-aoe-loot`
- `mod-ah-bot-plus`
- `mod-duel-reset`
- `mod-emblem-transfer`
- `mod-guildhouse`
- `mod-instance-reset`
- `mod-npc-enchanter`
- `mod-reagent-bank`
- `mod-playerbots`
- `mod-player-bot-level-brackets`
- `mod-individual-progression`
- `mod-guild-mate`
- `mod-transmog-plus`
- `mod-ollama-chat`

The 12 vendored upstream modules retain their source snapshots, including upstream license files and source notices where supplied, with local changes where needed for Bygdok integration. Guild Mate, Transmog Plus and Ollama Chat retain their Bygdok-maintained implementations. The recorded upstream commits describe their origins, not a requirement to download or reset them during a build.

Edit a module directly in its directory, then run `npm run build`. The runtime build calls [`scripts/stage-server-modules.py`](scripts/stage-server-modules.py) to replace generated module copies and remove stale `mod-*` directories from the AzerothCore build tree. Do not edit those generated copies. The auction-house module is staged as `mod-ah-bot` because its pinned script-loader symbol requires that directory name; its repository source remains under `mod-ah-bot-plus`.

This removes **module downloads**, not all downloads: AzerothCore, native dependencies, the Pocket Realm database-provider APK, Android tooling and Gradle dependencies retain their existing build requirements. The old Termux installers have been removed.

## How the runtime fits together

- **AzerothCore** provides the game server. The build fetches [`duall/azerothcore-android`](https://github.com/duall/azerothcore-android) at commit `abc884520173084d5cd37b72b57b3822230dcb32`, applies the repository's compatibility patches and compiles it with the local modules.
- **MariaDB** stores accounts, characters, world state and playerbot data. The local build extracts the MariaDB `12.3.2` provider from the pinned Pocket Realm `v0.103.0-alpha` APK, verifying its SHA-256 before use. The provider APK is a build input, not a second app users must install.
- **The Android app** packages the native executables and shared libraries, then its foreground service manages MariaDB, `authserver` and `worldserver` in the app's private environment. It installs configs and database content, manages startup, downloads missing game data and streams logs.
- **Persistent storage** holds realm data, downloaded game data and saved module settings separately from the APK. Generated runtime configs are rebuilt from bundled defaults and saved preferences.

The local provider staging helper is [`tools/stage-pocket-realm-mariadb.py`](tools/stage-pocket-realm-mariadb.py). It does not invoke [`scripts/build-mariadb-runtime.sh`](scripts/build-mariadb-runtime.sh), which is a separate MariaDB `10.11.9` source-build path still referenced by CI. Those provider paths are not interchangeable: the current app expects the Pocket Realm provider layout, including its manifest and bootstrap files. Do not assume CI runtime artifacts have the same provider contract as the local build.

## Repository guide

- [`android/`](android/) — Android app, realm lifecycle service, runtime integration, and tests.
- [`modules/`](modules/) — All selected vendored and Bygdok-maintained gameplay modules, with their provenance manifest.
- [`configs/`](configs/) — Canonical AzerothCore and module configuration.
- [`scripts/`](scripts/) — Android dependency, MariaDB, and AzerothCore runtime build scripts.
- [`tools/stage-pocket-realm-mariadb.py`](tools/stage-pocket-realm-mariadb.py) — Pinned Pocket Realm provider extraction for local builds.
- [`patches/`](patches/) — Android compatibility patches for the pinned server and database versions.
- [`runtime/`](runtime/) — Generated runtime build outputs and reserved packaging locations.
- [`.github/workflows/`](.github/workflows/) — CI workflows for runtime builds, APK packaging, and integration.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — Detailed architecture notes.
- [`docs/RUNTIME.md`](docs/RUNTIME.md) — Detailed runtime investigation notes.

The full AzerothCore source is fetched at a pinned revision by the build process; this repository contains the Bygdok-specific modules, configs, patches, scripts, app and packaging integration. Generated build trees are not the source of truth. The detailed documents and upstream module READMEs may contain historical Termux or older-runtime instructions; use this README for the current project workflow.

## Development and validation

Run the local build-orchestration and module-staging tests from the repository root; neither requires installing npm packages:

```powershell
npm run test:build
python -m unittest discover -s scripts\tests -p "test_*.py" -v
```

The Python tests use only the standard library and verify the exact module selection, offline staging, local edits, the auction-house alias and stale-module removal.

For Android JVM tests:

```powershell
Push-Location .\android
.\gradlew.bat :app:testDebugUnitTest
Pop-Location
```

Android Gradle tasks stage existing native runtime artifacts from [`runtime/build/`](runtime/build/); they do not compile changed C++ modules. Run `npm run build` for a complete native-and-app build before device testing.

When changing gameplay modules, edit their local source rather than generated copies. Preserve upstream licenses and provenance. Validate database initialization, server startup, game-data setup, client connection, mod behavior and data persistence on a physical device before treating a change as production-ready. APK assembly alone is not gameplay validation.

GitHub Actions remains available for runtime builds, APK packaging and integration, with artifacts named `bygdok-mariadb-arm64`, `bygdok-runtime-arm64` and `bygdok-eternal-debug`. The repository revision and its local module sources should determine each build; test artifacts on the integration device before merging.

## Credits

- [`duall/singlePlayerWow-android`](https://github.com/duall/singlePlayerWow-android) — Original project this repository was forked from.
- [`Pocket Realm`](https://github.com/puzzled-pancake/pocket-realm) — Foundation for the single-app Android hosting approach and the pinned local MariaDB provider.
- [`AzerothCore`](https://www.azerothcore.org/) and the contributors to the bundled modules — Server and gameplay foundations. See individual module licenses and [`modules/manifest.json`](modules/manifest.json) for provenance.
