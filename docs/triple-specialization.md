# Triple talent specializations

## Server integration

The server change is carried as
[`patches/azerothcore-android/0017-triple-talent-specializations.patch`](../patches/azerothcore-android/0017-triple-talent-specializations.patch)
and applies to the AzerothCore commit pinned by
[`scripts/build-server-runtime.sh`](../scripts/build-server-runtime.sh)
(`mod-playerbots/azerothcore-wotlk` commit
`32fd3472aa74961c91cb9bc942ee3e0a44b36779`).
The existing server build applies repository patches automatically; rebuild
with `npm run build` and deploy the resulting app/runtime using the normal
Bygdok Eternal process.

The existing character schema already has room for three groups:

- `characters.talentGroupsCount` and `characters.activeTalentGroup` are unsigned
  bytes.
- `character_talent.specMask` is an unsigned byte; the third slot uses bit 2.
- `character_glyphs.talentGroup` is an unsigned byte and each group has six
  independent glyph columns.
- `character_action.spec` stores independent action bars per group.

The patch raises the core limit to three and uses those existing fields. No
database migration is required, so deployment leaves current talent masks,
glyph rows, and active-slot values unchanged. Slot 3 starts with no talents or
glyphs; its action bar is initialized from the current bar when the slot is
unlocked. The existing trainer gossip option adds slot 3 under the existing
dual-specialization level and talent-reset eligibility checks. Existing one-
and two-slot characters are not automatically granted slot 3.

Invalid stored counts and active indices are logged and normalized during
character loading. Server activation and spell-effect indices are checked
before indexing spec arrays. Core unit tests cover valid counts and slot-index
bounds in `src/test/server/game/Entities/TripleTalentSpecTest.cpp` within the
patched AzerothCore tree.

## Native client and MPQ status

The user's original build-12340 client was subsequently inspected locally.
Its native talent/glyph interface files were extracted from the enUS archives
for analysis only; neither those original files nor the executable are
distributed in this repository. A client MPQ has not yet been produced or
runtime-tested.
The available 3.3.5 interface-file reference has
[`MAX_TALENT_GROUPS = 2` in `TalentFrameBase.lua`](https://github.com/wowgaming/3.3.5-interface-files/blob/d0339b17b0221db76e6acd2dc2915d224a5b62ca/TalentFrameBase.lua);
that Lua-side limit can be changed through an MPQ patch.
The documented 3.3.5 API contract for
[`SetActiveTalentGroup`](https://wowpedia.fandom.com/wiki/API_SetActiveTalentGroup)
accepts group indices 1 and 2, while the core's existing talent-info packet
already transmits a group count and group records. These facts show both that
the UI has an MPQ-changeable limit and that the native activation API only
documents two groups. Inspection of the actual executable now establishes a
native limitation, rather than inferring one from the API documentation.

### Confirmed build-12340 executable limitations

The inspected executable has SHA256
`aa63a5750d60ef16746c686b3d5e26876d98953eab08b1c026cd0faf78e88cb8`.
Addresses below are virtual addresses with image base `0x00400000`, not file
offsets; they apply only to this exact executable.

- The registered `SetActiveTalentGroup` implementation is at `0x005c5e70`.
  It converts the Lua group argument to a zero-based index and checks both the
  advertised group count and a separate hardcoded limit. At `0x005c5ed7`,
  `cmp eax, 2` followed by `jae` at `0x005c5eda` returns without activating
  the third group.
- The activation table at `0x00ad05e8` contains only spells `63645` and
  `63644`. The following word is unrelated API registration data, not a
  third activation spell. Raising the bound alone would read the wrong data.
- The player talent update path at `0x005c9e50` passes capacity two
  (`push 2` at `0x005c9e79`) to the reader at `0x005c9740` and also passes
  two to the preview/reconciliation path at `0x005c9eaf`.
- In that reader, the record index is compared with the supplied capacity at
  `0x005c97f5`. An index beyond capacity uses a null destination
  (`0x005c9804`), so its talent/glyph data is consumed but not stored.
  Talent writes are conditional on that destination at `0x005c982e`;
  glyph writes are likewise conditional at `0x005c986b`. Thus the stock
  reader deliberately discards a third record; raising its capacity without
  expanding the storage is what would introduce overlap.
- Player-group records begin at `0x00c21030` with observed stride `0x54`.
  The separate inspected-player record begins at `0x00c210d8`, exactly two
  strides later. Expanding the count without relocating/expanding storage
  would overlap that record.
- The player-group cleanup path at `0x005c9a30` initializes its loop count
  to two (`mov dword ptr [ebp+8], 2` at `0x005c9a4e`). A third record must
  participate in cleanup as well as loading, otherwise allocations and
  glyph/talent state would survive incorrectly between updates.
- Local instruction-level analysis found 84 references to the existing
  player/inspect/preview record region across talent APIs, preview handling,
  packet processing, and lifecycle code. This is an investigation inventory,
  not a verified exhaustive relocation list: indirect references, static
  initialization/destruction, and data references must also be accounted for
  before shipping an executable transformation.

An MPQ-only implementation is therefore insufficient for full native
operation on this executable. A complete implementation requires a
version-checked executable transformation for group storage, relevant
readers/initialization/cleanup/preview APIs, and the activation bound/table;
a third activation spell recognized by both client and server; and an MPQ
transformation of the native FrameXML controls. The activation effect's slot
index also needs protection from progression power scaling, independently of
the count-only fix in patch 0018.

The original client and live character have not been modified during this
investigation. Do not advertise three groups to the stock client or distribute
a bounds-only executable patch. End-to-end validation must cover display,
talents, glyphs, preview handling, activation restrictions, and persistence
for all three slots before calling the native implementation complete.

## Verification status

### Targeted unlock diagnostics

The server patch includes targeted unlock diagnostics.
`Logger.spells.spec=4,Console Server` enables low-volume `SPEC_DIAG_V1`
messages without global debug logging. After deploying the rebuilt APK and
restarting the realm, select the affected character and cast `.cast self 63624`
once. Inspect `Server.log` for the dispatcher mode, effect value, target,
script suppression, and specialization count before/after the update.
Then check `/run print(GetNumTalentGroups())` and verify persistence after
logout. A queued talent packet is not proof that the client accepted it.
The diagnostic effect handler also rejects invalid counts before narrowing
the signed spell-effect value to an unsigned byte.

### Progression scaling compatibility

[`0018-unscaled-talent-spec-count.patch`](../patches/azerothcore-android/0018-unscaled-talent-spec-count.patch)
makes specialization-count effects (`SPELL_EFFECT_TALENT_SPEC_COUNT`) bypass
`Unit::ApplyEffectModifiers`. Individual Progression's Vanilla power aura
applies a percentage modifier to all effects; without this exemption, a
two-slot unlock at level 60 with the default 0.6 scaling becomes 1.2 and is
truncated to one slot. Only count effects are exempted: combat scaling, other
effects in the same spell, and specialization activation effects are unchanged.
No progression settings, unlock requirements, or character data are modified.
The fix was compiled and packaged using `npm run build`. A focused C++ harness
executed the production modifier method with a simulated 0.6 power modifier:
counts 1, 2, and 3 remain unchanged in all three effect positions, while combat,
activation, sibling-effect, and no-modifier-owner behavior are preserved.
All 13 build/deployment tests passed, and the rebuilt ARM64 function was
inspected to confirm its count-only early return. Live unlock verification
requires deployment of this APK; it has not yet been performed.

The diagnostic revision of the patch was checked against the exact pinned
commit and compiled successfully using `npm run build`, including Android APK
assembly. All 13 `npm run test:build` build/deployment tests passed. The APK's
ARM64 worldserver was verified to match the staged library and contain all five
diagnostic markers; the dedicated logging directive is present in its assets.

The core unit test covers slot-count and index rules but has not been executed;
run it from a configured AzerothCore test build (`ctest -R '^unit$'`). The live
logout/login, restart, talent/glyph isolation, trainer purchase, packet, and
client UI scenarios require an operational worldserver and a 3.3.5a client.
The diagnostics are intended to investigate the observed dual-spec unlock
failure, not to claim that it is resolved.
