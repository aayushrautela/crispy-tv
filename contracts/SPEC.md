# Contract Spec (v1)

This directory defines parity-critical behavior for the rewrite apps.

## Determinism Rules

- Every fixture must include:
  - `contract_version`
  - `suite`
  - `case_id`
- Time-sensitive suites must include `now_ms` and use injected clocks in implementation.
- Any randomness must be driven by an injected seeded RNG.
- Output ordering must be canonical and explicitly defined by each suite.

## Active Suites

- `player_machine`
  - Event-driven playback transitions and engine fallback behavior.
- `media_ids`
  - Canonical media ID normalization and type mapping.
- `metadata_addon_primary`
  - Addon-first metadata merge and source precedence.
- `storage_v1`
  - Logical storage namespace/versioning and schema mismatch behavior.

## Breaking Changes

Breaking behavior changes are allowed when needed. Every breaking change must:

1) bump the suite fixture `contract_version`
2) update this spec
3) include migration notes in the PR description
