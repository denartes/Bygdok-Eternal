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

No client executable, extracted 3.3.5a FrameXML tree, or MPQ authoring tool is
part of this repository, so a client MPQ was not produced or runtime-tested.
This is not a claim that executable modification has been proven unavoidable.
The available 3.3.5 interface-file reference has
[`MAX_TALENT_GROUPS = 2` in `TalentFrameBase.lua`](https://github.com/wowgaming/3.3.5-interface-files/blob/d0339b17b0221db76e6acd2dc2915d224a5b62ca/TalentFrameBase.lua);
that Lua-side limit can be changed through an MPQ patch.
The documented 3.3.5 API contract for
[`SetActiveTalentGroup`](https://wowpedia.fandom.com/wiki/API_SetActiveTalentGroup)
accepts group indices 1 and 2, while the core's existing talent-info packet
already transmits a group count and group records. These facts show both that
the UI has an MPQ-changeable limit and that the native activation API only
documents two groups. They do not establish whether the executable rejects a
third group or whether a carefully changed FrameXML can use the existing
activation path. The client must be tested to determine whether
`SetActiveTalentGroup(3)` is accepted and whether login talent data can be
represented safely.

To complete and validate native client support, test build 12340 with a server
that advertises three groups. If the stock `SetActiveTalentGroup(3)` call is
rejected or the client fails to represent a third group, a native executable
change is required for the client's active-group API/handling and any
hard-coded group storage or packet bounds, in addition to the FrameXML
controls. Do not distribute or call an MPQ-only patch complete until all three
slots can be displayed, activated, and switched back in that client.

## Verification status

The repository-side test added here validates slot-count and index rules. The
server patch must also be compiled and the core unit test run from a configured
AzerothCore test build (`ctest -R '^unit$'`). The live logout/login, restart,
talent/glyph isolation, trainer purchase, packet, and client UI scenarios
require an operational worldserver and a 3.3.5a client; none are claimed as
executed by this change.
