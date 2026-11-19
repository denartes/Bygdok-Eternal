const assert = require("node:assert/strict");
const { test } = require("node:test");
const { mkdtempSync, mkdirSync, writeFileSync, rmSync } = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { deploy, findAdb, parseArgs } = require("../deploy-local.cjs");

function fixture(t) {
  const directory = mkdtempSync(path.join(os.tmpdir(), "bygdok-deploy-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const apk = path.join(directory, "app debug.apk");
  writeFileSync(apk, "test APK");
  return { directory, apk };
}

test("deploy installs the existing APK as an update on the selected device", t => {
  const { apk } = fixture(t);
  const calls = [];
  deploy({
    apk, adb: "test-adb",
    execute: (...args) => {
      calls.push(args);
      return "List of devices attached\nphone123\tdevice product:test\n";
    }
  });
  assert.deepEqual(calls, [
    ["test-adb", ["devices", "-l"], { capture: true }],
    ["test-adb", ["-s", "phone123", "install", "-r", apk]]
  ]);
});

test("check validates prerequisites without installing", t => {
  const { apk } = fixture(t);
  const calls = [];
  deploy({ apk, checkOnly: true, execute: (...args) => {
    calls.push(args);
    return "List of devices attached\nphone123\tdevice\n";
  } });
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0][1], ["devices", "-l"]);
});

test("missing and empty APKs stop before ADB runs", t => {
  const { directory, apk } = fixture(t);
  writeFileSync(apk, "");
  for (const file of [apk, path.join(directory, "missing.apk"), directory]) {
    assert.throws(() => deploy({ apk: file, execute: () => assert.fail() }), /Run npm run build first/);
  }
});

test("disconnected unauthorized offline and ambiguous devices fail without installation", t => {
  const { apk } = fixture(t);
  for (const [output, message] of [
    ["", /No Android device/],
    ["phone unauthorized", /unauthorized/],
    ["phone offline", /offline/],
    ["phone device\nemulator device", /Multiple Android devices/]
  ]) {
    let count = 0;
    assert.throws(() => deploy({ apk, execute: () => { count++; return output; } }), message);
    assert.equal(count, 1);
  }
});

test("serial selects exactly one connected authorized device", t => {
  const { apk } = fixture(t);
  const calls = [];
  deploy({ apk, serial: "phone", execute: (...args) => {
    calls.push(args);
    return "phone device\nemulator device\n";
  } });
  assert.equal(calls[1][1][1], "phone");
  assert.throws(() => deploy({ apk, serial: "missing", execute: () => "phone device" }), /not connected/);
});

test("ADB install failure propagates without uninstall or retry", t => {
  const { apk } = fixture(t);
  let count = 0;
  assert.throws(() => deploy({ apk, execute: () => {
    count++;
    if (count === 1) return "phone device";
    throw new Error("INSTALL_FAILED_UPDATE_INCOMPATIBLE");
  } }), /INSTALL_FAILED_UPDATE_INCOMPATIBLE/);
  assert.equal(count, 2);
});

test("finds ADB from SDK environment variables or escaped Gradle local.properties", t => {
  const { directory } = fixture(t);
  const sdk = path.join(directory, "SDK with spaces");
  const tools = path.join(sdk, "platform-tools");
  mkdirSync(tools, { recursive: true });
  const adb = path.join(tools, "adb.exe");
  writeFileSync(adb, "");
  const options = { platform: "win32", projectRoot: directory };
  assert.equal(findAdb({ ...options, env: { ANDROID_HOME: sdk } }), adb);
  assert.equal(findAdb({ ...options, env: { ANDROID_HOME: "missing", ANDROID_SDK_ROOT: sdk } }), adb);
  mkdirSync(path.join(directory, "android"));
  writeFileSync(path.join(directory, "android", "local.properties"),
    `sdk.dir=${sdk.replace(/\\/g, "\\\\").replace(/:/g, "\\:")}\n`);
  assert.equal(findAdb({ ...options, env: {} }), adb);
});

test("falls back to ADB on PATH when no SDK is configured", t => {
  const { directory } = fixture(t);
  assert.equal(findAdb({ env: {}, projectRoot: directory }), "adb");
});

test("parses supported CLI arguments and rejects invalid ones", () => {
  assert.deepEqual(parseArgs([]), {});
  assert.deepEqual(parseArgs(["--check", "--serial", "phone"]), { checkOnly: true, serial: "phone" });
  for (const args of [["--serial"], ["--serial", "--check"], ["--unknown"]]) {
    assert.throws(() => parseArgs(args), /Usage:/);
  }
});
