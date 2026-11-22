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

## Baseline and status

The ForeverChanges pages compare against Classic Era, not this project's
WotLK 3.3.5a baseline. The AzerothCore source tree is fetched during the server
build at commit `32fd3472aa74961c91cb9bc942ee3e0a44b36779` and is not checked
into this repository. The client DBC files are downloaded at runtime and also
are not present in the checkout. Therefore the WotLK behavior, actual required
change, implementation details, and dependencies are explicitly pending in
each CSV row. No Classic-to-Forever difference is assumed to be missing from
WotLK.

The export provides page text and HTML, not normalized records. It captures
all visible talent/spell rows and their descriptions, but expanded rank details
are available only for the initially selected talent on each page. Spell ranks,
complete talent rank values, prerequisite coordinates, and client/server
records must be verified against accessible Forever reference data and the
target WotLK data before implementation decisions are made.

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

1. Fetch the pinned AzerothCore/Playerbot source and inspect the relevant spell,
   talent, and bot behavior for every inventory row.
2. Compare the 3.3.5a client DBCs and talent/spell UI representation with the
   documented Forever behavior.
3. Resolve row dependencies and update the status and implementation fields
   with evidence-based differences.
4. Implement verified missing changes in `mod-forever-changes`, then validate
   client patch needs and Playerbot behavior against the same baseline.
