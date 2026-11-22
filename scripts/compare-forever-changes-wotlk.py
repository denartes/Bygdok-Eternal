#!/usr/bin/env python3
"""Compare Forever class entries with repository-tracked WotLK data."""

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs" / "forever-changes-inventory.csv"
SPELL_NAMES = ROOT / "modules" / "mod-llm-chatter" / "tools" / "spell_names.json"
TALENT_DATA = ROOT / "modules" / "mod-llm-chatter" / "tools" / "talent_data.json"
CLASS_MASKS = {
    "Warrior": 1,
    "Paladin": 2,
    "Hunter": 4,
    "Rogue": 8,
    "Priest": 16,
    "Shaman": 64,
    "Mage": 128,
    "Warlock": 256,
    "Druid": 1024,
}
FIELDS = [
    "Class",
    "Feature",
    "Feature type",
    "Talent tree or spell group",
    "Forever change type",
    "Forever behaviour",
    "WotLK behaviour",
    "Required change",
    "Server-side work",
    "Client-side work",
    "Playerbot work",
    "Dependencies",
    "Status",
    "Reference URL",
]


def normalize(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.casefold())


def legacy_names(description: str) -> list[str]:
    patterns = (
        r"\bReplaces\s+(.+?)(?:[.;]|$)",
        r"\bRenamed from\s+(.+?)(?:[.;]|$)",
    )
    names = []
    for pattern in patterns:
        names.extend(match.strip(" '\"") for match in re.findall(pattern, description, re.I))
    return [name for name in names if name]


def load_spell_ranks(
    path: Path, updates_directory: Path
) -> dict[int, list[tuple[int, int, int]]]:
    """Read the base AzerothCore spell-rank table and apply its world updates."""
    if not updates_directory.is_dir():
        raise ValueError(f"World SQL updates directory does not exist: {updates_directory}")
    contents = path.read_text(encoding="utf-8")
    rows = re.findall(r"\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)", contents)
    if not rows:
        raise ValueError(f"No spell_ranks rows found in {path}")
    rows_by_spell_id = {}
    for first_spell_id, spell_id, rank in rows:
        rows_by_spell_id[int(spell_id)] = (
            int(first_spell_id), int(spell_id), int(rank)
        )

    for update in sorted(updates_directory.glob("*.sql")):
        sql = update.read_text(encoding="utf-8")
        if not re.search(r"\bspell_ranks\b", sql, re.I):
            continue
        deletes = re.findall(
            r"DELETE\s+FROM\s+`?spell_ranks`?\s+WHERE\s+`?spell_id`?\s+IN\s*"
            r"\(([^)]*)\)",
            sql,
            re.I,
        )
        for deleted_ids in deletes:
            for spell_id in re.findall(r"\d+", deleted_ids):
                rows_by_spell_id.pop(int(spell_id), None)

        inserts = re.findall(
            r"INSERT\s+INTO\s+`?spell_ranks`?(?:\s*\([^)]*\))?\s*VALUES\s*(.*?);",
            sql,
            re.I | re.S,
        )
        for values in inserts:
            inserted = re.findall(
                r"\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)", values
            )
            for first_spell_id, spell_id, rank in inserted:
                spell_id = int(spell_id)
                rows_by_spell_id[spell_id] = (
                    int(first_spell_id), spell_id, int(rank)
                )

    chains: dict[int, list[tuple[int, int, int]]] = defaultdict(list)
    for row in rows_by_spell_id.values():
        chains[row[0]].append(row)
    for spells in chains.values():
        spells.sort(key=lambda item: item[2])
    return chains


def load_wotlk_data():
    spell_map = json.loads(SPELL_NAMES.read_text(encoding="utf-8"))["spell_names"]
    spells_by_name: dict[str, list[int]] = defaultdict(list)
    for spell_id, name in spell_map.items():
        spell_id = int(spell_id)
        spells_by_name[normalize(name.replace("\\", ""))].append(spell_id)

    talent_map = json.loads(TALENT_DATA.read_text(encoding="utf-8"))
    tabs = {int(tab_id): tab for tab_id, tab in talent_map["tabs"].items()}
    talents_by_name: dict[str, dict[tuple[int, int], dict]] = defaultdict(dict)
    for spell_id, entry in talent_map["spells"].items():
        talent_id, tab_id, rank, tier, column, name = entry
        tab = tabs.get(int(tab_id))
        if not tab or not name:
            continue
        key = (int(talent_id), int(tab_id))
        talent = talents_by_name[normalize(name)].setdefault(
            key,
            {
                "talent_id": int(talent_id),
                "tab_id": int(tab_id),
                "tree": tab["name"],
                "class_mask": int(tab["class_mask"]),
                "tier": int(tier),
                "column": int(column),
                "ranks": {},
            },
        )
        talent["ranks"][int(rank)] = int(spell_id)
    return spells_by_name, talents_by_name


def spell_rank_summary(
    spell_ids: list[int],
    rank_chains: dict[int, list[tuple[int, int, int]]],
) -> str:
    ids = set(spell_ids)
    matching_chains = []
    covered = set()
    for chain in rank_chains.values():
        if ids.intersection(spell_id for _, spell_id, _ in chain):
            matching_chains.append(chain)
            covered.update(spell_id for _, spell_id, _ in chain)
    matching_chains.sort(key=lambda chain: chain[0][0])
    if matching_chains:
        parts = []
        for chain in matching_chains[:8]:
            parts.append(
                f"{chain[0][0]}: "
                + ", ".join(
                    f"rank {rank}={spell_id}" for _, spell_id, rank in chain
                )
            )
        if len(matching_chains) > 8:
            parts.append(f"{len(matching_chains) - 8} more same-name chains")
        return (
            f"{len(ids)} exact-name spell IDs; AzerothCore rank chains "
            + "; ".join(parts)
            + ". Name mapping does not identify which same-name chain belongs "
            "to this class."
        )
    samples = ", ".join(map(str, sorted(ids)[:20]))
    suffix = f"; {len(ids) - 20} more IDs" if len(ids) > 20 else ""
    return (
        f"{len(ids)} exact-name Spell.dbc ID(s) in repository spell_names.json "
        f"({samples}{suffix}); no chain found in the pinned spell_ranks SQL."
    )


def compare_row(row, spells_by_name, talents_by_name, rank_chains):
    class_name = row["Class"]
    key = normalize(row["Feature"])
    spell_ids = spells_by_name.get(key, [])
    talent_matches = [
        talent
        for talent in talents_by_name.get(key, {}).values()
        if talent["class_mask"] & CLASS_MASKS[class_name]
    ]
    legacy_matches = []
    for old_name in legacy_names(row["Forever behaviour"]):
        old_key = normalize(old_name)
        old_talents = [
            talent
            for talent in talents_by_name.get(old_key, {}).values()
            if talent["class_mask"] & CLASS_MASKS[class_name]
        ]
        if old_talents:
            legacy_matches.extend((old_name, talent) for talent in old_talents)
        elif spells_by_name.get(old_key):
            legacy_matches.append((old_name, spells_by_name[old_key]))

    if row["Feature type"] == "Talent":
        if talent_matches:
            matches = []
            for talent in sorted(
                talent_matches, key=lambda item: (item["tab_id"], item["talent_id"])
            ):
                ranks = sorted(talent["ranks"].items())
                forever_tree = row["Talent tree or spell group"]
                tree_note = (
                    f"; Forever tree label is {forever_tree!r}"
                    if forever_tree and normalize(forever_tree) != normalize(talent["tree"])
                    else ""
                )
                matches.append(
                    f"{talent['tree']} (Talent.dbc ID {talent['talent_id']}, "
                    f"tier {talent['tier'] + 1}, column {talent['column'] + 1}, "
                    f"{len(ranks)} ranks "
                    f"[{', '.join(str(spell_id) for _, spell_id in ranks)}]"
                    f"{tree_note})"
                )
            baseline = "WotLK Talent.dbc match: " + "; ".join(matches)
        elif spell_ids:
            baseline = (
                "No same-name talent for this class in repository talent_data.json; "
                "same-name spell record(s): "
                + spell_rank_summary(spell_ids, rank_chains)
            )
        elif legacy_matches:
            details = []
            for old_name, matches in legacy_matches:
                if isinstance(matches, dict):
                    details.append(
                        f"{old_name!r} is a WotLK {matches['tree']} talent "
                        f"(tier {matches['tier'] + 1}, column {matches['column'] + 1})"
                    )
                else:
                    details.append(
                        f"{old_name!r} has same-name WotLK spell record(s): "
                        + spell_rank_summary(matches, rank_chains).rstrip(".")
                    )
            baseline = (
                "No exact-name WotLK talent; Forever description points to "
                + "; ".join(details)
                + "."
            )
        else:
            baseline = (
                "No exact class talent or same-name spell in repository-tracked "
                "WotLK talent_data.json/spell_names.json."
            )
    elif spell_ids:
        baseline = spell_rank_summary(spell_ids, rank_chains)
        if talent_matches:
            baseline = baseline.rstrip(".") + (
                "; same-name WotLK talent record also exists: "
                + ", ".join(
                    f"{talent['tree']} tier {talent['tier'] + 1}, "
                    f"column {talent['column'] + 1}"
                    for talent in talent_matches
                )
                + "."
            )
    elif legacy_matches:
        baseline = (
            "No exact-name WotLK spell; Forever description points to "
            + "; ".join(
                f"{old_name!r} with WotLK spell ID(s) "
                + ", ".join(map(str, matches[:12]))
                if isinstance(matches, list)
                else f"{old_name!r} as a WotLK talent"
                for old_name, matches in legacy_matches
            )
            + "."
        )
    else:
        baseline = (
            "No exact same-name record in repository-tracked WotLK spell_names.json."
        )

    exists = bool(talent_matches) if row["Feature type"] == "Talent" else bool(spell_ids)
    kind = row["Forever change type"]
    if kind == "Was a talent" and talent_matches:
        required = (
            "The WotLK counterpart is a talent; Forever describes it as a trained "
            "spell. Verify spell learning, talent-tree removal, and point/refund "
            "behavior before implementing."
        )
    elif kind == "New":
        if exists:
            required = (
                "Exact-name WotLK class counterpart exists; compare the Forever target "
                "rank/effect/availability before deciding whether new server or "
                "client data is required."
            )
        elif row["Feature type"] == "Talent" and spell_ids:
            required = (
                "A same-name WotLK spell exists but no class talent row does; "
                "compare whether Forever adds a talent, changes spell availability, "
                "or uses a renamed equivalent."
            )
        elif legacy_matches:
            required = (
                "The Forever description points to a WotLK feature under an older "
                "name; compare the replacement's rank/effect/tree before treating "
                "it as wholly new."
            )
        else:
            required = (
                "Candidate missing WotLK feature by exact name; check aliases, "
                "replacements, and existing mechanics before adding it."
            )
    elif kind == "Removed":
        if exists:
            required = (
                "WotLK counterpart exists by exact name; verify whether Forever "
                "removes it and whether that removal is also required from WotLK."
            )
        elif row["Feature type"] == "Talent" and spell_ids:
            required = (
                "No same-name WotLK talent row exists, although same-name spell "
                "record(s) do; verify whether WotLK already lacks the talent or "
                "uses it as a spell before specifying a removal."
            )
        else:
            required = (
                "No exact-name WotLK counterpart to remove; verify renamed or "
                "equivalent WotLK features before closing this difference."
            )
    elif kind in {"Moved", "Changed"} and exists:
        required = (
            "WotLK counterpart exists; compare Forever rank/effect/tree/prerequisite "
            "details with this baseline before implementing a change."
        )
    elif kind == "Earlier" and exists:
        required = (
            "WotLK counterpart exists; rank IDs are recorded, but the tracked "
            "lookup has no class learning-level progression. Compare training level "
            "and rank acquisition with the Forever target."
        )
    elif kind == "Other races" and exists:
        required = (
            "The WotLK spell exists by name; race eligibility is not captured in "
            "these tracked lookups. Compare client race restrictions and server "
            "learning rules with the Forever target."
        )
    elif kind == "Renamed" and legacy_matches:
        required = (
            "Forever identifies a renamed WotLK feature; compare the old and new "
            "spell/talent records and effects instead of treating it as unrelated."
        )
    elif legacy_matches:
        required = (
            "The Forever description references an existing WotLK name; compare "
            "that feature's effects, ranks, and placement before treating this as new."
        )
    else:
        required = (
            "Exact-name lookup is insufficient to establish the WotLK difference; "
            "check renamed equivalents and mechanics against the full target data."
        )
    row["WotLK behaviour"] = baseline
    row["Required change"] = required
    row["Server-side work"] = (
        "Inspect pinned AzerothCore spell scripts and world SQL overrides for "
        "the identified WotLK spell/talent IDs; not yet reviewed."
    )
    row["Client-side work"] = (
        "Repository-tracked talent names/placement and spell names/ranks compared; "
        "full 3.3.5a client DBC values still need review against Forever data."
    )
    row["Playerbot work"] = (
        "Inspect Playerbot actions/strategies for the WotLK IDs before specifying "
        "whether AI changes are required."
    )
    row["Dependencies"] = (
        "Talent tree and rank data are recorded where available; feature "
        "interactions and prerequisite changes require full target-data review."
    )
    row["Status"] = "WotLK name/rank/talent layout compared; mechanics pending"
    return row


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--spell-ranks",
        required=True,
        type=Path,
        help="spell_ranks.sql from AzerothCore commit 32fd3472aa74961c91cb9bc942ee3e0a44b36779",
    )
    parser.add_argument(
        "--updates-dir",
        required=True,
        type=Path,
        help="Pinned core data/sql/updates/db_world directory",
    )
    parser.add_argument("--inventory", type=Path, default=INVENTORY)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    spells_by_name, talents_by_name = load_wotlk_data()
    rank_chains = load_spell_ranks(args.spell_ranks, args.updates_dir)
    with args.inventory.open(encoding="utf-8", newline="") as inventory_file:
        rows = list(csv.DictReader(inventory_file))
    if not rows:
        raise SystemExit(f"No inventory rows found in {args.inventory}")
    for row in rows:
        compare_row(row, spells_by_name, talents_by_name, rank_chains)

    output = args.output or args.inventory
    with output.open("w", encoding="utf-8", newline="") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"Compared {len(rows)} rows with WotLK talent/spell names and ranks; wrote {output}")


if __name__ == "__main__":
    main()
