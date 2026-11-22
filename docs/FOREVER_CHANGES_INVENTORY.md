# WoW Forever changes inventory

This is the phase-two inventory for the nine classes documented by the
ForeverChanges export. The row-level inventory is
[`forever-changes-inventory.csv`](forever-changes-inventory.csv). It includes
every visible class-page change row for talents and spells, along with the
captured Forever description, change label, and the required server, client,
and Playerbot investigation fields.
The current export produces 766 rows: 504 talents and 262 spells.

Regenerate the CSV after updating the source export with:

```sh
python3 scripts/generate-forever-changes-inventory.py
```

The source is [`../foreverchanges_export.json`](../foreverchanges_export.json).
The generator extracts only rendered change rows from each class page's HTML;
it does not copy scripts, advertisements, or other page content into the
inventory.

## WotLK baseline comparison

The ForeverChanges pages compare against Classic Era, so the inventory was
compared with repository-tracked WotLK spell-name and talent data plus the
pinned AzerothCore rank-chain data:

- `modules/mod-llm-chatter/tools/talent_data.json` contains the target client's
  WotLK talent spell IDs, rank numbers, talent IDs, tree masks, and positions.
- `modules/mod-llm-chatter/tools/spell_names.json` provides the tracked WotLK
  spell ID/name map.
- `spell_ranks.sql` from AzerothCore/Playerbot commit
  `32fd3472aa74961c91cb9bc942ee3e0a44b36779` supplies the server's spell rank
  chains. This is the same core pin used by
  [`scripts/build-server-runtime.sh`](../scripts/build-server-runtime.sh).
- The pinned core's `data/sql/updates/db_world` migrations are applied in
  filename order; the rank data includes its Borrowed Time chain update.

The `WotLK behaviour` and `Status` columns contain the per-row findings. Exact
class talent matches include the WotLK talent tree, tier, column, rank count,
and rank spell IDs. Spell matches include exact-name IDs and the AzerothCore
rank chains containing those IDs. New, removed, moved, and changed labels are
then interpreted against WotLK presence instead of treating every Classic-era
difference as missing.

For this export, 347/504 talent rows have an exact class-specific WotLK talent
match; another 46 have same-name spell records but no talent record for the
class. Twelve descriptions point to an older WotLK name: 11 resolve to talent
records, and one resolves to a spell record. The remaining 99 have no
exact-name or recognized old-name match in the tracked lookups. Of 262 spell
rows, 237 have an exact-name WotLK spell ID and 25 do not. These counts are
name/structure evidence, not a claim that every matched feature has the same
effect or that every unmatched feature is absent under another name.

Regenerate the inventory, then compare it against the pinned core SQL:

```sh
python3 scripts/generate-forever-changes-inventory.py
python3 scripts/compare-forever-changes-wotlk.py \
  --spell-ranks /path/to/azerothcore/data/sql/base/db_world/spell_ranks.sql \
  --updates-dir /path/to/azerothcore/data/sql/updates/db_world
```

`spell_names.json` and `talent_data.json` are already in the repository. The
core source itself is fetched at build time and is not checked in; use the
commit-pinned source specified by the build script for the rank SQL.

## Remaining comparison limits

The export provides page text and HTML, not normalized records. It captures
all visible talent/spell rows and their descriptions, but expanded Forever rank
details are available only for the initially selected talent on each page.
Spell-name matching does not identify which same-name spell chain is usable by
each class; the comparator flags that ambiguity instead of guessing. Talent
spell data provides WotLK placement, but not every effect or server rule.

The comparison does not yet include AzerothCore's runtime spell scripts,
world-database DBC overrides, or Playerbot action/strategy implementations.
Those are required follow-up reviews for each feature. `Required change`
identifies evidence-based candidates, while server/client/bot work remains
pending where the tracked name and rank data cannot establish behavior.
No Classic-to-Forever difference is assumed to be absent in WotLK.

| Class | Talent/spell rows |
|---|---:|
| Warrior | 78 |
| Hunter | 74 |
| Mage | 68 |
| Rogue | 87 |
| Priest | 93 |
| Warlock | 92 |
| Paladin | 93 |
| Druid | 96 |
| Shaman | 85 |

## Remaining work

1. Inspect pinned AzerothCore/Playerbot runtime scripts and world-database
   overrides for the compared spells, talents, and abilities.
2. Obtain complete Forever client spell/talent records, especially ranks and
   talent-tree positions not included in the exported expanded details.
3. Resolve row dependencies and update implementation fields with verified
   Forever-versus-WotLK differences.
4. Implement only verified missing changes in `mod-forever-changes`, then
   validate client patch needs and Playerbot behavior.
