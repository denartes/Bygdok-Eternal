# Bygdok Eternal Runtime

Generated Android ARM64 artifacts live under the ignored `build` directory:

- `bygdok-runtime-arm64`: AzerothCore executables, database runtime, SQL and libraries.
- `mariadb-arm64`: staged Pocket Realm database provider.
- `ollama-arm64`: actual Ollama daemon with built-in CPU support, Vulkan backend,
  AdrenoTools loader/helper libraries, licenses and version metadata.

The Android build packages executables using APK-safe native-library names.
Ollama model weights are downloaded into app-private storage after explicit opt-in,
not bundled here. See the [project guide](../README.md#on-device-ai-chat-experimental).
Custom driver ZIPs may be kept under `drivers` locally (ignored by Git), but are
not build inputs or APK assets. Users import their own ZIP through the app.