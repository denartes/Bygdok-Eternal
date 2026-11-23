# Single-Button Assistant: Work Overview

This page records the Single-Button Assistant (SBA) work, current behavior,
verification, limitations, and the proposed runtime-editable rotation design.
The implementation is a **Retribution Paladin prototype**, not a complete
Retail rotation system.

## Background and decisions

The goal was a Retail-style single-button assistant for the AzerothCore 3.3.5a
realm, initially targeting Retribution Paladins. Bygdok Eternal runs the realm
on Android; the WoW client is separate. The chosen design therefore combines a
client addon with a server module. The client presents the action, while the
server chooses and attempts to cast the spell. An addon folder or MPQ patch can
deliver the client-side files; the APK alone does not update the WoW client.

The work progressed through these stages:

1. Implemented the initial server module, addon, Android module configuration,
   tests, and build integration.
2. The initial local build used an older core because the assistant branch had
   not yet incorporated the merged AzerothCore upgrade. The assistant work was
   subsequently integrated with the merged main branch, including LLM Chatter
   and full realm backup functionality, and rebuilt. The user had made a full
   realm backup before that integration.
3. Investigated a report that the visible button did nothing with an attackable
   target selected. An in-combat requirement was identified and removed; this
   was a concrete issue, not proof of the only possible cause.
4. Added server-driven recommendations and an action-bar macro integration.
   Smart targeting was initially added, then **removed** at the user's request:
   targeting and auto-attack are now the macro's responsibility.
5. Replaced the rectangular Assist button with a movable, resizable spell icon,
   retained the existing macro action, and added a scoped core patch for silent
   SBA cast errors without making the cast a triggered spell.
6. Discussed JSON-defined, runtime-editable rotations. That is a **future
   proposal**, not functionality implemented by this work.

## Architecture

```text
WoW macro (optional targeting and auto-attack)
        |
        v
BygdokAssistant addon  -- addon self-whisper -->  AzerothCore SBA module
        ^                                           |
        |                                           v
        +---------- recommendation / ACK -------- shared rotation evaluator
                                                    |
                                                    v
                                            normal server spell cast
```

- The addon sends requests and displays the server's recommendation. It does
  not calculate the rotation or choose a spell locally.
- The server uses the same evaluator for the preview and an activation. Normal
  core cast validation remains authoritative.
- The floating icon and `/click BygdokAssistantAction` use the same request
  mechanism. The named action remains available to macros and keybindings.
- Playerbots are not changed by this work; consuming a shared evaluator remains
  a future possibility, not an implemented integration.

### Communication and updates

The addon uses the `BygdokSBA` addon-message prefix over a self-whisper. On
entering the world it sends `WATCH`; activation sends `PRESS`, and the server
responds with `ACK`. Acknowledgment indicates that the request was received,
not that a spell was successfully cast. The server sends `SPELL:<id>` and
`TARGET:<name>` state to subscribed clients, including an empty target state.
The addon currently uses the spell message for its visuals.

The server evaluates watched players every 250 ms and after activation. It
only sends a recommendation or target update when the corresponding value
changes, apart from the initial subscription update. Presses are limited to
one per 250 ms per player. Per-player subscription state is cleared on logout.

### Targeting and combat

SBA does not acquire or switch targets, start melee auto-attack or Auto Shot,
or control pets. It checks the player's selected living attackable target and
attempts a cast only when that target is valid. Attackable neutral and hostile
targets are treated using the core's attack-target validation. The macro below
handles target selection and melee auto-attack independently:

```text
#showtooltip
/cleartarget [dead][help]
/targetenemy [noexists][noharm]
/startattack
/click BygdokAssistantAction
```

The macro itself may change targets; SBA does not. Area abilities may still hit
other enemies according to normal game rules.

## Rotation and preview

The current evaluator is a small, hardcoded Paladin damage priority:

1. Hammer of Wrath when the target is at or below 20% health.
2. Divine Storm.
3. Crusader Strike.
4. Judgement.
5. Exorcism when Art of War is active.
6. Consecration.

Without a valid target, Judgement is the preferred opening recommendation when
learned. The evaluator selects only known spells and follows the core's spell
rank chain to known higher ranks, including linked custom ranks. A contextual
ready ability takes precedence over one on cooldown; if no contextual ability
is ready, the first contextual learned ability remains visible. If no
contextual entry is learned, another learned rotation entry may be shown as a
fallback. An unready fallback is not cast while its cooldown is active.

Range and insufficient resources do not by themselves remove a recommendation.
The icon communicates these states where client APIs provide them; a
recommendation is not a guarantee of castability. The core still checks
resources, range, line of sight, facing, global cooldown, equipment, and other
spell restrictions when casting.

**Scope:** The code checks for the Paladin class, but does not detect or enforce
the Retribution specialization. It is not a complete or optimized rotation,
and does not provide full rotations for all classes or specs. It supports
linked custom ranks of existing entries, not arbitrary new module abilities.
Those are separate pieces of work.

## Client interface

- The floating button is a square spell icon, approximately 40 x 40 by default.
- It displays the recommended spell texture, cooldown overlay, range and
  resource/usability coloring, and a spell tooltip.
- Normal click requests SBA activation. Shift-drag moves the icon; shift-scroll
  resizes it. Position and size are saved per character.
- `/sba` toggles the floating icon.
- Default Blizzard action-bar buttons containing a macro line
  `/click BygdokAssistantAction` have their icon texture updated. This does not
  replace the macro or its action identity. Custom action bars are not
  supported by that texture hook; the floating icon has the detailed cooldown
  and usability visuals.

The module avoids its own player-facing failure notifications, and the addon
silently clears a missing acknowledgment after a timeout. The
[core patch](../patches/azerothcore-android/0016-silent-normal-spell-casts.patch)
adds a flag used for SBA normal casts to suppress ordinary cast-error reporting
without changing the cast into a triggered spell or suppressing unrelated
casts. The core retains its special GM/invisibility error. Failed casts are
available in server debug logging when enabled. Real-client behavior of this
patch and the interface still needs testing.

## Configuration and installation

`SingleButtonAssistant.Enable` controls the module and defaults to enabled in
the [configuration template](../modules/mod-single-button-assistant/conf/mod_single_button_assistant.conf.dist).
The Android Mods configuration also includes SBA. The former
`SingleButtonAssistant.EngagementRange` setting is unused; it can be removed
from older saved configuration overrides. No SBA SQL migration was added.

1. Keep a current realm backup. From the repository root, build with:

   ```text
   npm run build
   ```

2. Install `android/app/build/outputs/apk/debug/app-debug.apk` as an update and
   restart the realm. If using the repository deploy script with multiple
   connected devices, select the device explicitly:

   ```text
   npm run deploy -- --serial <device-id>
   ```

   The deploy script uses an update install; do not uninstall or clear app data
   to work around signing or installation errors if the realm must be retained.
3. Separately install [BygdokAssistant](../modules/mod-single-button-assistant/addon/BygdokAssistant)
   under the WoW client's `Interface/AddOns` directory, or package those files
   in an appropriate client MPQ patch. Reload the UI or restart the client.
4. Create the macro above and place it on an action bar, or use the floating
   icon without the macro's targeting and auto-attack behavior.

A separately built server must apply the scoped core patch to the matching
AzerothCore revision before building this module. The repository build applies
the patches in `patches/azerothcore-android`.

## Verification and outstanding tests

The last reported rebuild after the silent-cast change succeeded. The APK's
embedded worldserver hash matched the rebuilt runtime binary, its SBA
configuration matched the module template, and the staged module source
matched the workspace. The focused C++ server test, mocked Lua 5.1 addon
behavior test, and 13 build/deploy orchestration tests passed. Earlier
integration tests also covered module staging, Android realm configuration,
backup functionality, and LLM schema handling. These are historical results,
not a claim that the tests were rerun when this page was written.

**Not yet verified:** installation and end-to-end gameplay on the user's
device/client, including the redesigned icon and scoped error suppression.
Automated core stubs and Lua mocks are not substitutes for the actual client
and realm.

Suggested in-game checks:

1. With an early-level Paladin who knows only Judgement, check the no-target
   preview and ensure Judgement stays visible through its cooldown.
2. Learn another listed ability; check priority changes as cooldowns expire,
   Art of War changes, and target health crosses the execute threshold.
3. Check range and mana colors, cooldown overlay, tooltip, shift-drag,
   shift-scroll, and position/size after UI reload and relog.
4. Verify the macro by click and keybinding, in and out of combat. Confirm
   SBA alone does not change the selected target or start attacks, and test
   both hostile and attackable neutral targets.
5. Test failed SBA casts and verify that normal cast restrictions still apply
   and unrelated game errors remain visible. Confirm no spell is cast merely
   by moving or resizing the icon.

## Proposed runtime JSON rotations

The discussed direction is to replace hardcoded priority definitions with
validated server-side data consumed by the **same** evaluator for preview and
execution. An admin could paste a JSON definition for a class/spec through a
future Android or in-game interface. The server could validate it, persist it,
and atomically activate it without a rebuild or realm restart. Invalid edits
should produce actionable errors and leave the last valid rotation active.
This would first require implementation, testing, and an APK rebuild.

For illustration only, a possible structure is:

```json
{
  "class": "PALADIN",
  "spec": "RETRIBUTION",
  "openingSpell": 20271,
  "priorities": [
    { "spell": 24275, "when": { "targetHealthPercentAtMost": 20 } },
    { "spell": 53385 },
    { "spell": 35395 },
    { "spell": 20271 },
    { "spell": 879, "when": { "playerHasAura": 59578 } },
    { "spell": 26573, "target": "self" }
  ]
}
```

This is **not a supported schema or a complete rotation**. Design decisions
still include admin authorization, editing interface, storage and backup,
class/spec selection, rank resolution, target semantics, condition types,
limits and validation, reload synchronization, and error reporting. Custom
spells could be represented by ID only where the evaluator supports their
conditions and targeting. Editable data would not automatically provide the
missing class/spec rotations.

## Source map

- [Module README](../modules/mod-single-button-assistant/README.md) - concise
  module behavior, install instructions, and tests.
- [Server evaluator and protocol](../modules/mod-single-button-assistant/src/SingleButtonAssistant.cpp)
  and [loader](../modules/mod-single-button-assistant/src/single_button_assistant_loader.cpp).
- [Addon Lua](../modules/mod-single-button-assistant/addon/BygdokAssistant/BygdokAssistant.lua)
  and [addon manifest](../modules/mod-single-button-assistant/addon/BygdokAssistant/BygdokAssistant.toc).
- [Server and addon tests](../modules/mod-single-button-assistant/tests/) and
  [C++ test runner](../scripts/tests/test_single_button_assistant.py).
- [Android module configuration](../android/app/src/main/java/com/denartes/bygdoketernal/realm/RealmModConfig.kt),
  [module manifest](../modules/manifest.json), and
  [core cast-error patch](../patches/azerothcore-android/0016-silent-normal-spell-casts.patch).
