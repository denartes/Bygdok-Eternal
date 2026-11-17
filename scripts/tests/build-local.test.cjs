const assert = require("node:assert/strict");
const { test } = require("node:test");
const { build } = require("../build-local.cjs");

test("preflight checks Python, Java, and WSL without downloading or building", () => {
  const calls = [];
  build({ platform: "win32", checkOnly: true, execute: (...args) => calls.push(args) });
  assert.deepEqual(calls.map(call => call[0]), ["python", "java", "wsl.exe"]);
  assert.equal(calls[2][1].at(-1), "--check");
});

test("build orders provider staging, native build, and APK assembly", () => {
  const calls = [];
  // Prevent depending on an existing APK: stop at the final external command.
  assert.throws(() => build({
    platform: "win32",
    execute: (command, args, options) => {
      calls.push({ command, args, options });
      if (calls.length === 6) throw new Error("reached Gradle");
    }
  }), /reached Gradle/);
  assert.equal(calls.length, 6);
  assert.match(calls[3].args[0], /stage-pocket-realm-mariadb\.py$/);
  assert.equal(calls[4].command, "wsl.exe");
  assert.equal(calls[4].args.at(-1), "scripts/build-local-native.sh");
  assert.match(calls[5].args.at(-1), /gradlew\.bat" :app:assembleDebug"$/);
  assert.equal(calls[5].options.windowsVerbatimArguments, true);
});

test("preflight failure stops before downloads or builds", () => {
  let count = 0;
  assert.throws(() => build({
    platform: "win32",
    execute: () => { count++; throw new Error("missing prerequisite"); }
  }), /missing prerequisite/);
  assert.equal(count, 1);
});

test("unsupported hosts fail explicitly", () => {
  assert.throws(() => build({ platform: "linux", execute: () => assert.fail() }), /Windows with WSL/);
});
