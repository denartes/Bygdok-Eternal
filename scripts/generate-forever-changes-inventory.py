#!/usr/bin/env python3
"""Generate a normalized inventory from the ForeverChanges page export."""

import argparse
import csv
import json
import re
from html.parser import HTMLParser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPORT = ROOT / "foreverchanges_export.json"
OUTPUT = ROOT / "docs" / "forever-changes-inventory.csv"
EXPECTED_CLASSES = {
    "warrior", "paladin", "hunter", "rogue", "priest",
    "shaman", "mage", "warlock", "druid",
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
VOID_TAGS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
}
CHANGE_KINDS = {
    "v2-kind-added": "New",
    "v2-kind-modified": "Changed",
    "v2-kind-removed": "Removed",
    "v2-kind-moved": "Moved",
    "v2-kind-earlier": "Earlier",
    "v2-kind-baseline": "Was a talent",
    "v2-kind-race": "Race restriction changed",
    "v2-kind-renamed": "Renamed",
}


def clean(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


class ChangeRows(HTMLParser):
    """Extract the page's visible change rows without parsing unrelated HTML."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[dict[str, str]] = []
        self.current: dict[str, str] | None = None
        self.depth = 0
        self.name_depth: int | None = None
        self.description_depth: int | None = None
        self.status_depth: int | None = None
        self.scope_depth: int | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        classes = (attributes.get("class") or "").split()
        if tag == "button" and "v2-row" in classes:
            self.current = {
                "name": "",
                "description": "",
                "status": "",
                "scope": "",
                "kind": next(
                    (kind for css_class, kind in CHANGE_KINDS.items() if css_class in classes),
                    "",
                ),
            }
            self.rows.append(self.current)
            self.depth = 1
            return

        if self.current is None:
            return

        if tag not in VOID_TAGS:
            self.depth += 1
        if tag == "strong":
            self.name_depth = self.depth
        elif tag == "small":
            self.scope_depth = self.depth
        elif tag == "span" and "v2-tag" in classes:
            self.status_depth = self.depth
        elif tag == "span" and "v2-row-text" in classes:
            self.description_depth = self.depth

    def handle_endtag(self, tag: str) -> None:
        if self.current is None:
            return
        if tag == "button":
            self.current = None
            self.depth = 0
            self.name_depth = None
            self.description_depth = None
            self.status_depth = None
            self.scope_depth = None
            return

        if self.depth == self.name_depth:
            self.name_depth = None
        if self.depth == self.description_depth:
            self.description_depth = None
        if self.depth == self.status_depth:
            self.status_depth = None
        if self.depth == self.scope_depth:
            self.scope_depth = None
        if tag not in VOID_TAGS:
            self.depth -= 1

    def handle_data(self, data: str) -> None:
        if self.current is None:
            return
        if self.name_depth is not None:
            self.current["name"] += data
        elif self.scope_depth is not None:
            self.current["scope"] += data
        elif self.status_depth is not None:
            self.current["status"] += data
        elif self.description_depth is not None:
            self.current["description"] += data


def inventory_rows(export: dict[str, dict[str, str]]) -> list[dict[str, str]]:
    if set(export) != EXPECTED_CLASSES:
        raise ValueError(
            "Export must contain exactly the nine documented classes; found "
            + ", ".join(sorted(export))
        )

    rows = []
    for class_name, page in export.items():
        parser = ChangeRows()
        parser.feed(page.get("html", ""))
        if not parser.rows:
            raise ValueError(f"No change rows found for {class_name}")

        for item in parser.rows:
            name = clean(item["name"])
            description = clean(item["description"])
            status = clean(item["status"]) or item["kind"]
            scope = clean(item["scope"])
            if not name or not status or not item["kind"]:
                raise ValueError(f"Incomplete change row for {class_name}: {item!r}")

            feature_type = scope.split("·", 1)[0].strip()
            tree_or_group = scope.split("·", 1)[1].strip() if "·" in scope else ""
            rows.append(
                {
                    "Class": class_name.title(),
                    "Feature": name,
                    "Feature type": feature_type,
                    "Talent tree or spell group": tree_or_group,
                    "Forever change type": status,
                    "Forever behaviour": description,
                    "WotLK behaviour": (
                        "Not compared. Inspect the pinned AzerothCore/Playerbot "
                        "baseline and 3.3.5a data before deciding."
                    ),
                    "Required change": (
                        "Undetermined pending the WotLK comparison; preserve "
                        "existing behavior until a documented difference is verified."
                    ),
                    "Server-side work": (
                        "Pending comparison of WotLK spell/talent data and server "
                        "behavior; add module scripts or SQL only for verified gaps."
                    ),
                    "Client-side work": (
                        "Pending 3.3.5a DBC/client-data comparison; patch client "
                        "data only where required for matching display or validation."
                    ),
                    "Playerbot work": (
                        "Pending Playerbot baseline review; change bot actions or "
                        "priorities only where this feature requires it."
                    ),
                    "Dependencies": (
                        "Not established; resolve related talents, spells, ranks, "
                        "and mechanics during baseline comparison."
                    ),
                    "Status": "Inventory captured; WotLK comparison not started",
                    "Reference URL": page.get("url", ""),
                }
            )
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path, default=EXPORT)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()

    export = json.loads(args.export.read_text(encoding="utf-8"))
    rows = inventory_rows(export)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as output_file:
        writer = csv.DictWriter(output_file, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} class spell/talent entries to {args.output}")


if __name__ == "__main__":
    main()
