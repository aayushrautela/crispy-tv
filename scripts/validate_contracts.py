#!/usr/bin/env python3

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator


SUITE_TO_SCHEMA = {
    "player_machine": "player_machine.schema.json",
    "media_ids": "media_ids.schema.json",
    "id_prefixes": "id_prefixes.schema.json",
    "catalog_url_building": "catalog_url_building.schema.json",
    "search_ranking_and_dedup": "search_ranking_and_dedup.schema.json",
    "metadata_addon_primary": "metadata_addon_primary.schema.json",
    "metadata_tmdb_enhancer": "metadata_tmdb_enhancer.schema.json",
    "sync_planner": "sync_planner.schema.json",
    "storage_v1": "storage_v1.schema.json",
    "continue_watching": "continue_watching.schema.json",
    "trakt_scrobble_policy": "trakt_scrobble_policy.schema.json",
}


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    schemas_root = root / "contracts" / "schemas"
    fixtures_root = root / "contracts" / "fixtures"

    schema_cache = {}
    failures = []

    for fixture_path in sorted(fixtures_root.rglob("*.json")):
        try:
            fixture = load_json(fixture_path)
        except json.JSONDecodeError as error:
            failures.append(
                f"{fixture_path.relative_to(root)}: invalid JSON ({error.msg} at line {error.lineno})"
            )
            continue

        suite = fixture.get("suite")
        if suite not in SUITE_TO_SCHEMA:
            failures.append(f"{fixture_path.relative_to(root)}: unknown suite '{suite}'")
            continue

        schema_name = SUITE_TO_SCHEMA[suite]
        if schema_name not in schema_cache:
            schema_cache[schema_name] = load_json(schemas_root / schema_name)

        validator = Draft202012Validator(schema_cache[schema_name])
        errors = sorted(validator.iter_errors(fixture), key=lambda err: list(err.path))
        for error in errors:
            path = ".".join(str(part) for part in error.path) or "<root>"
            failures.append(
                f"{fixture_path.relative_to(root)} [{path}]: {error.message}"
            )

    if failures:
        print("Contract validation failed:\n")
        for line in failures:
            print(f"- {line}")
        return 1

    fixture_count = sum(1 for _ in fixtures_root.rglob("*.json"))
    print(f"Validated {fixture_count} contract fixture(s) successfully.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
