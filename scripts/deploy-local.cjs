const { spawnSync } = require("node:child_process");
const { existsSync, readFileSync, statSync } = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const defaultApk = path.join(root, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk");

function findAdb({ env = process.env, platform = process.platform, projectRoot = root } = {}) {
  const sdkPaths = [env.ANDROID_HOME, env.ANDROID_SDK_ROOT];
  const properties = path.join(projectRoot, "android", "local.properties");
  if (existsSync(properties)) {
    const sdk = readFileSync(properties, "utf8").match(/^\s*sdk\.dir\s*=\s*(.+?)\s*$/m)?.[1];
    if (sdk) sdkPaths.push(sdk.replace(/\\([\\:= ])/g, "$1"));
  }
  for (const sdk of sdkPaths.filter(Boolean)) {
    const adb = path.join(sdk, "platform-tools", platform === "win32" ? "adb.exe" : "adb");
    if (existsSync(adb) && statSync(adb).isFile()) return adb;
  }
  return "adb";
}

function run(adb, args, { capture = false } = {}) {
  const result = spawnSync(adb, args, {
    cwd: root,
    stdio: capture ? ["ignore", "pipe", "inherit"] : "inherit",
    encoding: "utf8"
  });
  if (result.error) {
    throw new Error(`Could not run ADB: ${result.error.message}. Install Android SDK Platform Tools and configure ANDROID_HOME or android/local.properties, or add adb to PATH.`);
  }
  if (result.status !== 0) {
    throw new Error(`ADB failed (${result.signal || `exit ${result.status}`}). See its output above. Do not uninstall or clear app data to resolve an update/signing error if you need to keep your realm.`);
  }
  return result.stdout || "";
}

function deploy({ checkOnly = false, serial, apk = defaultApk, adb = findAdb(), execute = run } = {}) {
  if (!existsSync(apk) || !statSync(apk).isFile() || statSync(apk).size === 0) {
    throw new Error(`No non-empty debug APK at ${apk}. Run npm run build first.`);
  }
  const output = execute(adb, ["devices", "-l"], { capture: true });
  const devices = output.split(/\r?\n/).map(line => line.trim())
    .filter(line => line && !line.startsWith("List of devices") && !line.startsWith("*"))
    .map(line => {
      const [id, state] = line.split(/\s+/);
      return { id, state };
    });
  if (!serial && devices.length !== 1) {
    throw new Error(devices.length === 0
      ? "No Android device connected. Enable USB debugging, connect the phone, and authorize this computer."
      : "Multiple Android devices connected. Select one with npm run deploy -- --serial <device-id>.");
  }
  const device = serial ? devices.find(device => device.id === serial) : devices[0];
  if (!device) throw new Error(`Android device ${serial} is not connected.`);
  if (device.state !== "device") {
    throw new Error(`Android device ${device.id} is ${device.state}. Unlock the phone, authorize USB debugging, and reconnect if necessary.`);
  }
  if (checkOnly) {
    console.log(`[deploy] APK and device ${device.id} ready; nothing installed.`);
    return;
  }
  console.log(`[deploy] Installing existing APK on ${device.id} as an update (no uninstall or data clear)...`);
  execute(adb, ["-s", device.id, "install", "-r", apk]);
  console.log("[deploy] APK installed. Open Bygdok Eternal on the phone.");
}

function parseArgs(args) {
  const options = {};
  for (let index = 0; index < args.length; index++) {
    if (args[index] === "--check") options.checkOnly = true;
    else if (args[index] === "--serial" && args[index + 1] && !args[index + 1].startsWith("--")) {
      options.serial = args[++index];
    } else throw new Error("Usage: npm run deploy [-- --check] [-- --serial <device-id>]");
  }
  return options;
}

if (require.main === module) {
  try {
    deploy(parseArgs(process.argv.slice(2)));
  } catch (error) {
    console.error(`[deploy] ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = { deploy, findAdb, parseArgs };
