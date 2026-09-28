#!/usr/bin/env python3

"""Parse the GitHub Actions workflows, rejecting duplicate mapping keys.

## Why this exists

A duplicate key in a workflow file is not a style problem, it is an outage with
no error message anywhere useful. It was committed to `android.yml` and
`android-release.yml` — two `run:` lines under one step — and the only symptom
was two runs that appeared on push, completed `failure` in under a second, and
were listed by *file path* instead of by their `name:`. The workflows never ran
at all, so nothing inside them could report the problem.

That is the specific trap: the failure surfaces in GitHub's UI as a red run with
an empty log, and the natural conclusion is "the tests failed", when in fact the
tests never started. Locally nothing notices, because Gradle tasks are invoked
directly and no YAML is ever read.

## What it checks

`yaml.safe_load` happily takes the last value for a repeated key, so a plain
parse proves nothing. This loads through a constructor that raises on the second
occurrence of any key in any mapping, at any depth.

The check is hermetic and read-only: it touches no network and no Gradle, so it
belongs in `check-local.sh` before the expensive tasks, and in CI as its own step
so a malformed workflow is reported as a workflow problem rather than as a test
failure.

Usage:
    validate_workflows.py
"""

import pathlib
import sys

try:
    import yaml
except ModuleNotFoundError as error:  # pragma: no cover - environment guard
    sys.exit(
        "validate_workflows: PyYAML is required. Install the tooling dependencies with "
        "`pip install -r requirements.txt` (or use the repo venv).\n"
        f"  import error: {error}"
    )

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"


class StrictLoader(yaml.SafeLoader):
    """A SafeLoader that refuses to silently discard a repeated key."""


def _mapping_no_duplicates(loader: StrictLoader, node: yaml.MappingNode, deep: bool = False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                None,
                None,
                f"duplicate key {key!r}",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


StrictLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _mapping_no_duplicates,
)


def check(path: pathlib.Path) -> list[str]:
    """Returns a list of human-readable problems; empty means the file is fine."""
    try:
        parsed = yaml.load(path.read_text(), Loader=StrictLoader)
    except yaml.YAMLError as error:
        return [f"  {path.relative_to(REPO_ROOT)}: {error}"]

    problems = []
    if not isinstance(parsed, dict):
        return [f"  {path.relative_to(REPO_ROOT)}: top level is not a mapping"]

    # `on` is parsed by YAML 1.1 as the boolean True, which is correct YAML and
    # useless to read. Normalise it so the report below is legible.
    triggers = parsed.get("on", parsed.get(True))
    if triggers is None:
        problems.append(f"  {path.relative_to(REPO_ROOT)}: no `on:` trigger")
    elif isinstance(triggers, str):
        pass
    elif isinstance(triggers, dict) and not triggers:
        problems.append(f"  {path.relative_to(REPO_ROOT)}: `on:` is empty")

    jobs = parsed.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        problems.append(f"  {path.relative_to(REPO_ROOT)}: no jobs declared")
    else:
        for job_name, job in jobs.items():
            if not isinstance(job, dict):
                problems.append(f"  {path.relative_to(REPO_ROOT)}: job {job_name!r} is not a mapping")
                continue
            for step in job.get("steps") or []:
                if isinstance(step, dict) and "uses" in step and "run" in step:
                    problems.append(
                        f"  {path.relative_to(REPO_ROOT)}: job {job_name!r} has a step with both "
                        "`uses` and `run`"
                    )
    return problems


def main() -> int:
    if not WORKFLOWS_DIR.is_dir():
        print(f"validate_workflows: no {WORKFLOWS_DIR}", file=sys.stderr)
        return 1

    workflows = sorted(WORKFLOWS_DIR.glob("*.y*ml"))
    if not workflows:
        print("validate_workflows: no workflow files found", file=sys.stderr)
        return 1

    problems: list[str] = []
    for path in workflows:
        problems += check(path)
        print(f"  ok  {path.relative_to(REPO_ROOT)}")

    if problems:
        print("validate_workflows: FAILED", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"validate_workflows: {len(workflows)} workflow(s) parse cleanly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
