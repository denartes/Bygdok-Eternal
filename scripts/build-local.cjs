const { spawnSync } = require("node:child_process");
const { existsSync, statSync } = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, stdio: "inherit", ...options });
  if (result.error) throw new Error(`Could not run ${command}: ${result.error.message}`);
  if (result.status !== 0) {
    throw new Error(`${command} failed (${result.signal || `exit ${result.status}`})`);
  }
}

function build({ checkOnly = false, platform = process.platform, execute = run } = {}) {
  if (platform !== "win32") {
    throw new Error("This local build command currently supports Windows with WSL (Ubuntu).");
  }

  execute("python", ["--version"]);
  execute("py", ["-3.10", "--version"]);
  execute("java", ["-version"]);
  // --cd accepts the Windows workspace path, including spaces, without shell interpolation.
  const nativeArgs = ["--cd", root, "--exec", "bash", "scripts/build-local-native.sh"];
  execute("wsl.exe", [...nativeArgs, "--check"]);
  if (checkOnly) {
    console.log("[build] Prerequisites checked; no downloads or builds performed.");
    return;
  }

  console.log("[build] Staging the pinned Pocket Realm database provider...");
  execute("python", [path.join(root, "tools", "stage-pocket-realm-mariadb.py")]);
  console.log("[build] Building native dependencies and AzerothCore from local module sources...");
  execute("wsl.exe", nativeArgs);
  console.log("[build] Building the Android debug APK...");
  const wrapper = path.join(root, "android", "gradlew.bat");
  execute(process.env.ComSpec || "cmd.exe", ["/d", "/s", "/c", `""${wrapper}" :app:assembleDebug"`], {
    cwd: path.join(root, "android"),
    windowsVerbatimArguments: true
  });
  const apk = path.join(root, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk");
  if (!existsSync(apk) || statSync(apk).size === 0) {
    throw new Error(`Build finished without a non-empty APK: ${apk}`);
  }
  console.log(`[build] APK ready: ${apk}`);
}

if (require.main === module) {
  try {
    const args = process.argv.slice(2);
    if (args.some(arg => arg !== "--check")) throw new Error("Usage: npm run build [-- --check]");
    build({ checkOnly: args.includes("--check") });
  } catch (error) {
    console.error(`[build] ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = { build };
