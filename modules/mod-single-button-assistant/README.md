# Single Button Assistant

The SBA is a first-pass Retribution Paladin damage assistant, not a complete or
optimized class rotation. The addon sends fixed `PRESS` requests over the
AzerothCore addon whisper channel. The server uses one rotation evaluator for
the preview and execution; Lua does not choose spells.

The server never selects a target or starts auto-attack. It casts only against
the player's selected living attackable target, including attackable neutral
creatures. An existing target is never replaced. For target acquisition and
auto-attack, put this macro on an action bar:

```
#showtooltip
/cleartarget [dead][help]
/targetenemy [noexists][noharm]
/startattack
/click BygdokAssistantAction
```

The addon updates the texture of default action-bar slots containing the
`/click BygdokAssistantAction` line. The action and macro remain unchanged;
custom action bars are not supported. The movable icon uses the same server
request as the macro. Shift-drag to move it; shift-scroll to resize it. Its
position and size are saved per character. `/sba` toggles its visibility.

The server re-evaluates subscribed players every 250 ms and after a press,
sending only changed spell and target state. A spell may be recommended without
a target or while on cooldown; a ready contextual ability takes priority over
one on cooldown, otherwise the first contextual ability remains visible. The
client displays spell cooldown, resource usability and target range using the
3.3.5a spell APIs. The display is advisory: normal core cast checks remain
authoritative, including line of sight, facing, global cooldown and equipment.

The current priority is Hammer of Wrath at or below 20% target health, Divine
Storm, Crusader Strike, Judgement, Exorcism with Art of War, and Consecration.
Without a target, Judgement is the preferred opening spell. Only learned spells
are considered; the core's spell-rank chain is followed for learned higher
ranks, including additional ranks supplied by modules. When no contextual
spell is learned, the first learned rotation spell is shown as a fallback.
There are no full rotations for other classes or specializations in this
module. A recommendation is not a guarantee of castability, and area attacks
may hit additional enemies.

The module emits no SBA notification on failed presses, and the addon does not
display missing-acknowledgment warnings. A scoped AzerothCore patch provides a
silent normal-cast flag for SBA, without bypassing normal cast checks or
suppressing errors from other casts. Server debug logging records failed cast
results when enabled. The core retains its special GM/invisibility error.

Toggle the module with `SingleButtonAssistant.Enable` in
`mod_single_button_assistant.conf`. The old `SingleButtonAssistant.EngagementRange`
setting is unused and can be removed from existing saved configurations. No SQL
changes are needed.

## Build and installation

Run `npm run build` at the repository root and install
`android/app/build/outputs/apk/debug/app-debug.apk` as an update, then restart
the realm. Separately install `addon/BygdokAssistant` under the WoW client's
`Interface/AddOns` directory, or package that path in an MPQ patch. Reload the
client UI or restart the client. The APK does not update the client addon. A
separately built server must also apply
`patches/azerothcore-android/0016-silent-normal-spell-casts.patch` to the
matching AzerothCore revision before building this module.

## Tests

Run `python3 -m unittest scripts.tests.test_single_button_assistant -v` on a
system with a C++17 compiler. With Lua 5.1 installed, run:

```
lua5.1 modules\mod-single-button-assistant\tests\addon_test.lua modules\mod-single-button-assistant\addon\BygdokAssistant\BygdokAssistant.lua
```

In game, test an early-level paladin with only Judgement learned, including its
cooldown and no-target preview. Learn another rotation spell and verify that
the recommendation changes as cooldowns expire and target health changes.
Check the icon's cooldown, range and mana colors, its tooltip, shift-drag,
shift-scroll, and persistence across reload and relog. Test the macro using
clicks and keybinds in and out of combat. Confirm that SBA alone never changes
the selection, and that hostile and attackable neutral targets both work.
Test failed casts and verify unrelated game errors are unaffected.
