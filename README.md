# Bygdok Eternal

Bygdok Eternal is an Android-hosted, single-player World of Warcraft: Wrath of the Lich King (3.3.5a) realm built on AzerothCore. It brings the realm server and its management into one Android app, with Playerbots and gameplay modules designed to make Azeroth feel active even when you play alone.

This repository is a fork of [`duall/singlePlayerWow-android`](https://github.com/duall/singlePlayerWow-android). Its Android-hosted server foundation builds on [`Pocket Realm`](https://github.com/puzzled-pancake/pocket-realm), adapted here for AzerothCore and WoW 3.3.5a and integrated with Bygdok Eternal's gameplay modules and configuration. The Android app replaces the inherited Termux-based server setup; users do not need to install Termux or Pocket Realm separately.

This README is the central project overview and local development guide.

## What it includes

- **AzerothCore server:** An Android ARM64 build of the pinned AzerothCore fork, including `authserver` and `worldserver`.
- **Embedded realm database:** MariaDB is packaged with the app runtime; the app is designed to initialize and manage it locally.
- **Android management app:** A Kotlin/Jetpack Compose app with initial setup, server start/stop and status, activity logs and console, account management, network settings, mod controls and configuration, and full realm backup export/restore.
- **Solo-play features:** Playerbots and a curated set of AzerothCore modules for progression, quality of life, and gameplay customization.
- **Bygdok modules:** Guild Mate for autonomous guild characters, feature-rich LLM Chatter for AI-powered bot dialogue, and Transmog Plus for slot-based transmogrification.
- **Separate game data:** Maps, vmaps, mmaps, and dbc data are downloaded on first run; they are not included in the APK.

The Android app hosts the realm server; it is not the WoW game client. You need a compatible 3.3.5a client to connect to the realm.

## Project status

The Android app and embedded-runtime build and orchestration are under active development. The repository includes CI workflows for the Android ARM64 MariaDB and AzerothCore runtimes and for packaging those runtimes into a debug APK.

The LLM Chatter integration, including its embedded Python bridge and updated Playerbots/core pins, is under active development and has not yet been validated end to end. Android JVM tests cover module configuration, persistence, dependency handling, bot-account management and database-client diagnostics; a successful APK or native build alone does not establish that the bridge and provider work on a phone.

The Samsung Galaxy S25 is the documented integration-test device. Treat the runtime as experimental until setup, startup, AI responses, gameplay and persistence have been confirmed there.

## Getting started

### Get a build

From the repository root in the Windows VS Code terminal, run:

```powershell
npm run build
```

No `npm install` is needed: npm is only the entry point, with no JavaScript package dependencies. The command stages the pinned Pocket Realm MariaDB provider, builds native dependencies and AzerothCore under WSL using the local modules, then runs the Windows Gradle wrapper to assemble the APK. It stops on the first failure.

The output is [`android/app/build/outputs/apk/debug/app-debug.apk`](android/app/build/outputs/apk/debug/app-debug.apk), for ARM64 Android 11 (API 30) or newer. The command builds the APK only; it does **not** install or update the app on your phone.

Prerequisites:

- Node.js 18 or newer, Python 3 on the Windows PATH, the Python 3.10 launcher target (`py -3.10`) for Chaquopy, and Java 17.
- An Android SDK configured for the Gradle project (through [`android/local.properties`](android/local.properties) or the standard Android SDK environment variables), with Android platform/build tools 35.
- A working default WSL Linux distribution with Python 3.9+, CMake, Ninja, Git, Make, Perl, curl, unzip, tar, GCC/G++, and pkg-config.
- The **Linux** Android NDK `29.0.14206865` installed under `~/android-sdk/ndk/` in WSL. Alternatively, make `ANDROID_NDK_ROOT` available in the WSL environment. The Windows NDK is not interchangeable with it.

To check the command's Python 3.10, Java, and WSL native prerequisites without downloading or building:

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

After a server upgrade, incompatible client data is replaced automatically using the compatible v20.0 data release. This requires another roughly 1.2 GB download and enough free space for both the old and new extracted data during the replacement. Existing data is retained until the new download has been extracted and checked; realm databases and character data are not reset.

Before launching authserver, the app clears the temporary version-mismatch flag that a failed worldserver startup can leave on realm 1. The realm remains offline until worldserver is ready, and other realm flags are preserved. This also applies when validating an imported realm; it does not reset accounts, characters, or other realms.

Connect using a compatible WoW 3.3.5a client configured with the realm address set in the app. Use the account created during setup. The game client is separate; this project does not require a particular client-running solution such as Winlator. Network access from another device depends on the address and network configuration; remote connectivity is not guaranteed by this README.

The dashboard also provides **Accounts**, **Network**, **Console**, **Backup** and **Mods** tabs.

### Full realm backup and restore

1. Save or discard mod edits, stop the realm, and wait until **all** components
   (including MariaDB and Ollama) are stopped.
2. Open **Backup > Export full realm** and choose a ZIP destination using
   Android's file picker. Save outside the app's private storage, such as
   Downloads or a document provider. Wait for the success message; do not use
   an interrupted or failed export.
3. Keep the ZIP and a copy of the APK used to create it somewhere safe.

The full backup includes the entire stopped MariaDB data directory: accounts,
characters, playerbots, world customizations, module tables, AI memories and
database users/grants. It also includes downloaded maps/vmaps/mmaps/DBC data,
Ollama model blobs/manifests, local AI state, imported GPU drivers and their
selection, saved mod configurations, realm server configurations, realm name,
setup status and network preferences. Empty files/directories are preserved.
Only APK-generated binaries, SQL/provider assets, temporary runtime files,
linker symlinks, sockets and PID files are excluded; they are recreated by the
installed app. Server settings survive restore, with device-specific paths
reapplied on startup.

After a fresh install, choose **Import existing realm** on the first-run
screen **before starting MariaDB**. Select the full backup instead of creating
a new realm. For an existing installation, stop all components and select
**Backup > Select backup archive**. Restore replaces the realm, mod settings,
models, drivers and app preferences rather than merging them. Every file's
SHA-256 and size are checked before replacement. The previous installation
is retained during replacement; failed/interrupted replacements roll back
using a persistent recovery journal when the realm service next starts.

These are **physical database snapshots**, not cross-version SQL migrations.
Restore requires the same bundled MariaDB executable as the exporting app;
incompatible runtimes are rejected before replacement. Using the same APK is
recommended to preserve core/module behavior too. A fresh install can then
start without re-downloading included client data or models. Network addresses
are restored unchanged; update them in **Network** if the device/network changed.
Imported drivers still require compatible hardware and Android versions.

Backups are **not encrypted** and can contain account credentials, provider
API keys and private chat history. Only restore trusted backups, especially
because they can include native GPU drivers. Large realms/models produce
multi-GB ZIPs; choose a destination supporting large files. Restore needs
space for the selected ZIP and its expanded contents in addition to any
current realm retained for rollback. The separate game client and its addons,
and models hosted by external providers, are outside app-owned storage and
are not included.

Legacy logical ZIP/tar.gz database imports remain supported, but they do not
provide the complete restoration guarantees of the app's new full backups.

### Managing mods

Open **Mods** in the dashboard while the server is stopped. All 15 bundled modules have an enable switch and **Configure** screen. Search for a configuration key, edit its value using the existing format (including quotes around strings), and select **Save settings**. Changes apply at the next server start; they are not live reloads. Unsaved editor changes are retained when switching tabs, and starting from Home returns to Mods until you save or discard them.

Settings are stored separately from the APK's templates in the app's private `files/mod-settings` directory. They survive rebuilding and installing an update with the **same application ID and signing key**, and are reapplied whenever effective server configs are generated. New bundled settings are merged in without overwriting saved values. Uninstalling, clearing app data, or switching to a separately installed application/signature does not preserve this storage. Module preferences are not a substitute for a realm backup. App-saved settings take priority over portable backup configs during import validation.

Guild Mate, Bot Level Brackets, and LLM Chatter are automatically held inactive when Playerbots is off, without erasing their own preferences. Auction House Bot and Duel Reset master switches disable both associated features, while retaining individual buyer/seller or cooldown/health settings for re-enabling.

Switches disable runtime behavior, not compiled module code or SQL migrations. Existing auctions, houses, stored reagents, progression data and appearances are not removed. In particular, disabling Individual Progression does not revert its world SQL. LLM Chatter can use the app-managed local Ollama provider or a separately configured compatible provider.

Emblem Transfer, Guildhouse and Reagent Bank now have working master switches, and NPC Enchanter suppresses its announcement when disabled. Run the full `npm run build` to compile those native module changes; assembling only the Android UI against old native binaries cannot add these server-side controls.

### Bot accounts and Guild Mate setup

You can configure existing accounts and guilds without starting authserver or worldserver or logging into the game:

1. Stop the realm, then open **Accounts → Bot links / Guild Mate exclusions**.
2. Select **Load / refresh database**. The app starts only MariaDB if needed.
3. Select your main account, search for other accounts or characters, then enable/disable their account links. Confirm each change. Links grant trusted Playerbots access **in both directions**, matching the in-game linking command; this is a local realm-administrator action and does not require the in-game security key.
4. Enable **Exclude from Guild Mate** for each human account. Disable it only for accounts whose characters should be eligible for autonomous login. Exclusions and account links are separate settings.
5. Open **Mods → Guild Mate → Configure**, load the guild list, select the existing guilds to use and choose **Save settings**.

Account links and exclusions are saved immediately in the realm database and verified by reading them back. Guild selections use the persistent mod-settings store and apply at the next realm start. Neither action creates accounts, creates guilds or adds characters to guilds; characters must already belong to a selected guild. Character lists show current guild membership, not a guarantee of bot eligibility. Guild Mate still respects random-bot filtering, account exclusions and its other configuration.

Trusted linked-account control requires `Playerbots.AllowTrustedAccountBots` to be enabled in Playerbots configuration. Linking is not required merely for Guild Mate to auto-login eligible members of a configured guild.

Controls are locked while the game servers are active or startup/import is in progress. MariaDB stays available after database management; use **Home → Stop database** when finished, or start the realm normally. Missing schemas and database errors are shown rather than treating a failed load as an empty list.

The local MariaDB client may warn that `--ssl-verify-server-cert` is disabled for its passwordless root connection. Administrative queries currently connect to the embedded database on `127.0.0.1`; this warning does not by itself mean the query failed. Client diagnostics are kept in Console logs, separate from database result rows. Actual query failures still stop the operation and display an error.

## Solo-play modules

- **Playerbots** provide autonomous characters and are a core part of the solo-play experience.
- **Guild Mate** can automatically run configured guild characters using Playerbots AI. Human accounts can be excluded.
- **LLM Chatter** adds contextual bot conversations and group, guild and roleplay features. Its Python bridge is embedded in the Android app; configure an Ollama, OpenAI-compatible or Anthropic provider in the module settings.
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
- `mod-llm-chatter`

The 13 vendored upstream modules retain their source snapshots, including upstream license files and source notices where supplied, with local changes where needed for Bygdok integration. Guild Mate and Transmog Plus retain their Bygdok-maintained implementations. The recorded upstream commits describe their origins, not a requirement to download or reset them during a build.

Edit a module directly in its directory, then run `npm run build`. The runtime build calls [`scripts/stage-server-modules.py`](scripts/stage-server-modules.py) to replace generated module copies and remove stale `mod-*` directories from the AzerothCore build tree. Do not edit those generated copies. The auction-house module is staged as `mod-ah-bot` because its pinned script-loader symbol requires that directory name; its repository source remains under `mod-ah-bot-plus`.

This removes **module downloads**, not all downloads: AzerothCore, native dependencies, the Pocket Realm database-provider APK, Android tooling and Gradle dependencies retain their existing build requirements. The old Termux installers have been removed.

## How the runtime fits together

- **AzerothCore** provides the game server. The build fetches the [`mod-playerbots/azerothcore-wotlk` Playerbot branch](https://github.com/mod-playerbots/azerothcore-wotlk/tree/Playerbot) at commit `32fd3472aa74961c91cb9bc942ee3e0a44b36779`, applies the repository's Android compatibility patches and compiles it with the local modules.
- **MariaDB** stores accounts, characters, world state and playerbot data. The local build extracts the MariaDB `12.3.2` provider from the pinned Pocket Realm `v0.103.0-alpha` APK, verifying its SHA-256 before use. The provider APK is a build input, not a second app users must install.
- **Database client compatibility:** Android's MariaDB Connector/C uses its own `3.x` version numbering. The core patches require Connector/C `3.3+` and matching compile-time/runtime connector versions, rather than comparing it to MySQL's `8.x` server-version macros. MySQL client checks and database server-version checks remain in place.
- **Realm address resolution:** The Android core patch avoids Bionic's unsupported IPv4 `AI_ALL` resolver flag and uses service `0` for address-only lookups. This applies to both authserver and worldserver, including realm addresses and subnet masks; hostname resolution remains enabled.
- **Guild House compatibility:** The bundled module is updated to upstream revision `0b3cc14e947a`, including current creature-schema SQL, creature ID compatibility and localized menus. Local `GuildHouse.Enable` behavior is retained. If startup previously failed on the removed `creature_template.scale` column, install the updated APK and retry; no database reset is needed.
- **AoE Loot compatibility:** Upstream revision `957f9a1a6861` replaces the obsolete `acore_string` locale-column SQL with `module_string`/`module_string_locale`, includes current packet APIs and loot-eligibility fixes, and adds per-character `.aoeloot on/off` commands. Optional inventory-overflow mailing defaults to disabled. Install the updated APK and retry after a `locale_koKR` migration failure; obsolete bundled SQL is removed on installation without resetting realm data.
- **Emblem Transfer compatibility:** Upstream revision `feb2007a50c7` removes the obsolete creature-template `scale` column while retaining the NPC's model scale in `creature_template_model.DisplayScale`. Local `EmblemTransfer.Enable` behavior is preserved. Retry startup with the updated APK after a `base_npc_emblem_transfer.sql` failure; pending transfers and realm data do not need resetting.
- **Instance Reset compatibility:** Upstream revision `1e2c20770f40` removes the obsolete creature-template `scale` column from the reset NPC migration, preserving model size and translations. Retry startup with the updated APK after a `mod_instance_reset_2024_03_14.sql` failure; existing instance saves do not need resetting.
- **NPC Enchanter compatibility:** Upstream revision `af32add66eaf` removes the obsolete creature-template `scale` column while preserving the enchanter's model, equipment and dialogue. The local enable-gated login announcement is retained. Retry startup with the updated APK after an `npc_enchanter.sql` failure; no realm data reset is needed.
- **Reagent Bank compatibility:** Upstream revision `62c265338ceb` removes the obsolete creature-template immunity-mask column from the banker migration. The existing enable/disable behavior and stored reagents are preserved. Retry startup after a `reagent_bank_NPC.sql` failure without resetting realm data.
- **Embedded bridge startup:** The Python bridge uses its configured runtime directory and Python's temporary-directory setting instead of changing native environment variables on its worker thread. This avoids an Android JNI crash when server startup reads the process environment concurrently.
- **The Android app** packages the native executables and shared libraries, then its foreground service manages MariaDB, `authserver`, `worldserver`, the embedded LLM Chatter Python bridge and optional local Ollama in the app's private environment. It installs configs and database content, manages startup, downloads missing game data and streams logs.
- **LLM Chatter bridge** runs in the app process using Chaquopy and connects to the embedded MariaDB over loopback. It starts and stops with the realm; bridge and request logs stay in app-private storage.
- The Android bridge pins its Python dependencies in [`android/app/requirements-llm-chatter.txt`](android/app/requirements-llm-chatter.txt) and uses small in-app HTTP adapters for the OpenAI-compatible Chat Completions and Anthropic Messages APIs.
- **On-device Ollama** is built from pinned Ollama `0.20.3` sources with Vulkan GPU support and CPU fallback. No Termux installation is required. Model weights are downloaded separately after opt-in.
- **Persistent storage** holds realm data, downloaded game data and saved module settings separately from the APK. Generated runtime configs are rebuilt from bundled defaults and saved preferences.

The bundled SQL source tree is replaced on realm startup rather than merged.
This removes obsolete migrations when a core upgrade moves them into its
archive, avoiding duplicate-filename updater failures. Only SQL source assets
are replaced; existing databases, characters and module import markers remain
untouched.

The local provider staging helper is [`tools/stage-pocket-realm-mariadb.py`](tools/stage-pocket-realm-mariadb.py). It does not invoke [`scripts/build-mariadb-runtime.sh`](scripts/build-mariadb-runtime.sh), which is a separate MariaDB `10.11.9` source-build path still referenced by CI. Those provider paths are not interchangeable: the current app expects the Pocket Realm provider layout, including its manifest and bootstrap files. Do not assume CI runtime artifacts have the same provider contract as the local build.

### AI chat (experimental)

With the realm stopped, open **Mods**, enable **Playerbots** and **LLM Chatter**.
The bridge is packaged in the app and managed with the realm; there is no
separate server to install. LLM Chatter defaults to Ollama at
`127.0.0.1:11435`, with `qwen2.5:0.5b` as the default model. App-managed
Ollama uses the saved `LLMChatter.Model` selection from Configure, including
its tag (for example `llama3.2:1b`), rather than overriding it. Use the
**Use on-device Ollama** button on the LLM Chatter
card. Select it to start the managed provider and allow the app to download
the model at realm start. Model weights are stored separately from the APK and
reused on later starts. Download size depends on the selected model.

Managed on-device mode serializes LLM calls before they reach Ollama and
prioritizes player-message requests ahead of queued background generation.
It disables speculative group pre-cache generation for that runtime session;
normal conversations, greetings, memory and gameplay reactions remain enabled.
Saved preferences and remote-provider behavior are unchanged. A request already
generating cannot be preempted; CPU-only inference can still be slow.

The bundled model is intentionally small and may not reliably follow LLM
Chatter's richer roleplay and structured prompts. For better responses, configure
a stronger local Ollama model or a hosted provider in **Mods → LLM Chatter →
Configure**. Hosted providers may require credentials and internet access;
configure secrets only on a trusted device. The module's complete provider,
prompt, personality, group-chat and memory options are available in its config.

The Android service starts the Python bridge before worldserver and stops it
during realm shutdown. It connects to the app's embedded MariaDB on loopback.
Import validation suppresses AI chat and never starts Ollama or downloads a
model. If bridge or provider startup fails, the app logs the error, disables
chat for that server session and allows gameplay to continue. Native Ollama
output is in app-private `realm/ollama/ollama.log`; the bridge log is in
`realm/llm-chatter/tmp/bridge.log` and request logs are under
`realm/llm-chatter/logs/`. Console tails the bridge log, including its startup
health check and provider errors. The provider status appears on Home.

The bundled MariaDB provider does not include the `ascii` character set.
LLM Chatter's screenshot request tokens use `utf8mb4_bin` instead, preserving
case-sensitive uniqueness. If an earlier integration build failed with
`Unknown character set: 'ascii'`, install the updated APK and start the realm
again; the failed base import is retried without resetting existing data.

The app-managed Ollama daemon listens only on `127.0.0.1:11435` and is stopped
with the realm. Its dynamic-library search path, including child runners,
contains only Ollama's C++ runtime and GGML base library. Server dependencies
are excluded: the bundled
`liblzma.so` otherwise shadows Android's version and prevents Vulkan loading
with a missing `Xzs_Construct` symbol in `libunwindstack.so`.

The optional Vulkan driver import remains available from the
LLM Chatter mod card; imported native drivers execute with the app's permissions,
so only use trusted ZIPs. On-device inference, bridge lifecycle and full
gameplay behavior still require validation on the integration phone.

#### Custom Vulkan drivers

With the realm stopped, open **Mods > LLM Chatter > Import driver ZIP** and pick
a trusted AdrenoTools-format ZIP using Android's file picker. Copy your ZIP to
the phone's Downloads folder first if it is currently on your PC. Import selects
the driver for the next local Ollama start; it does not enable AI chat or change
the phone's system driver. **Use system driver** removes the imported selection.
Remote Ollama is unaffected.

The importer accepts flat ZIPs with schema-1 `meta.json`, its named ARM64 ELF
shared library and optional sibling libraries/licence notices. It checks paths,
duplicate names, expanded size limits, Android API requirements and ELF headers
before atomically replacing the selected driver. Failed validation preserves the
previous driver. These checks do not prove that native code is trustworthy:
imported drivers execute with the app's permissions. Do not import unknown ZIPs.

Drivers stay in app-private storage across compatible updates, not uninstall or
Clear data, and are included in full realm backups (not legacy logical backups). Driver ZIPs are never bundled
in the APK or uploaded by the build. The optional local `runtime/drivers/*.zip`
directory is ignored by Git. The APK includes pinned BSD-licensed AdrenoTools
and its helper libraries; this loader is used only by the Ollama Vulkan backend.
Native logs show the requested driver and discovered device/driver identity.
GPU inference must still pass the startup self-test; failures trigger the
existing logged CPU recovery. Import support is not a guarantee of compatibility
with a particular driver, GPU, kernel or Android release.

Select **Use configured provider** to return to the saved endpoint/model configuration.
Local overrides apply only to generated runtime configs, not saved remote
preferences. Advanced settings `Bygdok.Ollama.Managed` and
`Bygdok.Ollama.AllowModelDownload` are available in Configure; disable the latter
to prohibit future model downloads.

[`scripts/build-ollama-runtime.sh`](scripts/build-ollama-runtime.sh) cross-compiles
the daemon and Vulkan/CPU backends using the Android NDK and a checksum-verified
Go toolchain. The local native build and CI include it automatically. Model
weights are not bundled in the APK. Building and JVM tests do not establish
on-device GPU compatibility; verify startup, generation and shutdown on the phone.

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

The standalone [`realm-resolver-test.cpp`](scripts/tests/native/realm-resolver-test.cpp) exercises the patched core resolver, including numeric addresses, subnet masks, hostname lookup, explicit ports and invalid inputs. Compile it with the Android NDK C++20 compiler and `-static-libstdc++ -pthread`, adding the staged Boost include directory and the patched core's `src/common` and `src/common/Utilities` include directories. Run the resulting executable on the Android device; all seven checks must pass. A desktop run alone cannot detect Bionic's resolver restrictions.

When changing gameplay modules, edit their local source rather than generated copies. Preserve upstream licenses and provenance. Validate database initialization, server startup, game-data setup, client connection, mod behavior and data persistence on a physical device before treating a change as production-ready. APK assembly alone is not gameplay validation.

GitHub Actions remains available for runtime builds, APK packaging and integration, with artifacts named `bygdok-mariadb-arm64`, `bygdok-runtime-arm64` and `bygdok-eternal-debug`. The repository revision and its local module sources should determine each build; test artifacts on the integration device before merging.

## Credits

- [`duall/singlePlayerWow-android`](https://github.com/duall/singlePlayerWow-android) — Original project this repository was forked from.
- [`Pocket Realm`](https://github.com/puzzled-pancake/pocket-realm) — Foundation for the single-app Android hosting approach and the pinned local MariaDB provider.
- [`AzerothCore`](https://www.azerothcore.org/) and the contributors to the bundled modules — Server and gameplay foundations. See individual module licenses and [`modules/manifest.json`](modules/manifest.json) for provenance.
