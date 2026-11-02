# QC framework

Manual and device QC for Semper, kept as data. A feature's checks are written
once as **scenarios** in a suite file. A **run** splits them into **lanes**:
each lane is one agent or person, with its own worktree, devices and accounts.
One script, [`scripts/qa/qa.py`](../scripts/qa/qa.py), checks the data, writes each
lane's checklist and brief, and merges the results into a report.

Why it is built this way is recorded in
[ADR-034](../docs/adr/ADR-034-qc-framework.md). Every field is described in
[SCHEMA.md](SCHEMA.md).

## What is where

```
qa/
  README.md            this page
  SCHEMA.md            every field, required or optional; how to extend it
  environments.toml    devices (emulators and real phones), builds, backends
  accounts.toml        QC account roles and institutions; addresses, never passwords
  lanes.toml           how a run is split into lanes, and the human gates
  suites/<name>.toml   one file per feature: its scenarios
  templates/
    lane-results.md    the results checklist `plan` writes for each lane
    lane-brief.md      the brief `plan` writes for each lane's agent
  runs/<run name>/     one folder per run: LANE-<X>.md, BRIEF-<X>.md, REPORT.md, evidence/
scripts/qa/qa.py       check | list | plan | report  (Python 3.11+, stdlib only)
scripts/qa/tests/      its tests
```

The first suite is [`suites/licence-activation.toml`](suites/licence-activation.toml)
(ADR-033: licence per app, explicit activation, device count, upgrade by issue).
[`suites/recycle-bin.toml`](suites/recycle-bin.toml) (prefix `R`, ADR-035) checks the
recycle bin: lane B runs its phone scenarios on two emulators, lane E its backend ones.

## Glossary

| Word | Meaning |
|------|---------|
| **Scenario** | One check: steps, the expected result, who and what it needs. A `[[scenario]]` table with a stable id such as `L3.5`. |
| **Suite** | One file of scenarios for a feature. Its `prefix` (`L`) starts every id in it. |
| **Group** | The part of an id before the dot (`L3`). A lane picks scenarios by group. |
| **Issued** | A suite's `[issued]` table: the highest number ever given out in each group. Ids up to it must all still exist. |
| **Needs** | What a scenario runs on: `emulator`, `device` (a real phone only), `web` (a console in a browser), `backend` (scripts, API calls, logs). |
| **Lane** | A slice of a run for one agent: devices, builds, a backend, and filters that pick scenarios. No two lanes share a device. |
| **Row** | One scenario on one device in a lane's results file. A scenario with no phone need has one row with device `-`. |
| **Human gate** | A step only a person may take (a staff step-up, a deploy, a Firestore write). The lane stops there and asks. |
| **Automation** | Where a scenario is (or will be) tested by code: `manual`, a reference such as `androidTest:PhonesActivityTest#deactivate`, or `planned:` plus a reference for a test not written yet. |
| **Run** | One pass over some lanes, kept in `runs/<name>/` so a later run can compare with it. |
| **Verdict** | A scenario's result over all its rows in a run: pass, fail, blocked, incomplete, skipped or not run. |

## A run, start to finish

1. **Check the data.** `python scripts/qa/qa.py check`. CI runs this on every
   push; locally it is the first thing to run after any edit under `qa/`.
2. **Write the lane files.**
   `python scripts/qa/qa.py plan --run qa/runs/2026-11-licence-activation`
   writes `LANE-<X>.md` (the results table) and `BRIEF-<X>.md` (the agent's
   brief) for every lane in `lanes.toml`, and an empty `evidence/`. Add
   `--lane C` to write only some lanes. It refuses to overwrite a lane file,
   which may hold results, unless you pass `--force`.
3. **Create the accounts.** Each brief lists the accounts and institutions its
   lane needs, with the lane's own addresses (`qc-ind1-a@…` for lane A). Staff
   create them on the staging console (`create-qc-accounts` gate).
4. **Run the lanes.** Give each agent its `BRIEF-<X>.md`. It fills
   `LANE-<X>.md`: result, build, evidence, note per row, and stops at each
   human gate to ask.
5. **Report.** `python scripts/qa/qa.py report --run qa/runs/2026-11-licence-activation`
   writes `REPORT.md`: the summary, a scenario × device matrix, the fails and
   blockers, and which scenarios passed on an emulator only or on a real phone
   too. Add `--compare qa/runs/<an earlier run>` to list every verdict that
   changed and mark regressions.
6. **Close out.** Each fail that is not fixed gets a row in
   [TECH_DEBT.md](../docs/ops/TECH_DEBT.md); put its `TD-NNN` in the row's
   note. `report` exits 1 while any fail's note names no `TD-`, `FI-` or `#`
   number. Commit the run folder.

### How a verdict is reached

A scenario **passes** when every real phone it ran on passed, at least one
emulator passed (if it ran on any), and, for a scenario with no phone, at
least one row passed. Any `fail` row makes it **fail**. Otherwise it is
**blocked** if a row is blocked, **incomplete** if some rows are still blank,
**skipped** if every row was skipped, and **not run** if none was filled.

### Which rows a lane gets

A lane takes the `active` scenarios of its `suites` whose group is in its
`groups`, plus any listed in `scenarios`; then `tags` (keep a scenario with
any of them) and `exclude_tags` narrow the group picks. Each scenario becomes
one row per lane device that can run it:

- needs `emulator`: every emulator and every real phone in the lane;
- needs `device` (and not `emulator`): real phones only;
- needs neither: one row with device `-`;
- a scenario's own `devices` narrows the phones further (for example
  `["tag:16k"]`). A scenario with no fitting device is not in that lane.

## Adding things

### A scenario

1. Open the suite file. Find the group; take the next number: if `[issued]`
   says `L3 = 6`, the new id is `L3.7`.
2. Raise `[issued]` to `L3 = 7`.
3. Copy a `[[scenario]]` table and fill every required field
   ([SCHEMA.md](SCHEMA.md#scenario)). Accounts, human gates, devices and
   builds must already exist in their files.
4. `python scripts/qa/qa.py check`. A warning that it is "in no lane" means no
   lane's `groups` or `scenarios` picks it.

To **retire** one, never delete it or reuse its id: set `status = "retired"`
and say why in `note` (and `replaced_by` if another scenario took over). A
scenario that cannot run yet is `status = "deferred"`, with the blocker in
`note`. `check` fails if an issued id is missing, and in CI also if an id that
exists on the base branch is gone.

### A lane, or re-splitting a run

Edit `lanes.toml`: a `[[lane]]` with its devices, builds, backend, suites and
`groups`. A device may be in one lane only. Then `plan` again for a new run.
Lanes are config: moving a group from lane A to lane B is a one-line edit.

### A device

Add a `[[device]]` to `environments.toml`: `kind = "emulator"` with its `avd`,
or `kind = "real"` with its `adb_device` (the `device:` that `adb devices -l`
shows). Give it `tags` (`api30`, `16k`, `arm64`), so lanes and scenarios can
pick it by `tag:<tag>`. A phone that must never be used stays listed with
`use = false` and the reason in `note`; `check` fails if a lane names it.

### A build or a backend

Add a `[[build]]` (its `recipe` is what the lane runs; `env` holds the
variables it sets in its own shell) or a `[[backend]]` to `environments.toml`.

### An account role or institution

Add an `[[account]]` to `accounts.toml`. The address carries `{lane}`, so
every lane has its own copy (`qc-x-{lane}@qa.sempermechanics.com`). It must be
on the file's `domain` (the backend's `TEST_ACCOUNT_DOMAINS`) unless
`test_account = false`, with a note saying why. Never a password, a TOTP
secret or a licence key. A member account names its `[[institution]]`.

### A human gate

Add a `[[gate]]` to `lanes.toml`: who does it and the exact ask. Scenarios
list it in `human_gate`; each brief lists the gates its lane will hit and the
scenarios that hit them.

### A suite (a new feature)

Create `suites/<name>.toml` with `schema`, `id` (the file name), `title`, a
`prefix` no other suite uses, `since` (the ADR or PR), `[issued]` and its
scenarios. Add the suite to the lanes that should run it. Nothing else
changes.

### An automation runner

A reference is `<runner>:<target>`. The runners are one table, `RUNNERS` in
`qa.py`: `androidTest` and `unitTest` (a Kotlin class name, found under
`app/src/androidTest` or `app/src/test`), `pytest`, `node` and `check` (a file
path from the repository root). Adding one (say `maestro:` for flow files) is
one line there and one row in [SCHEMA.md](SCHEMA.md#automation). Prefix a
reference with `planned:` until the test exists; `check --verbose` notes when
a planned file appears.

### A new field

Use an `x-` prefix (`x-owner = "…"`) until SCHEMA.md adopts it; `check`
rejects any other unknown field. Adopting it, or changing what a field means,
is a schema change: see [SCHEMA.md](SCHEMA.md#changing-the-schema).

## Commands

```bash
python scripts/qa/qa.py check                     # validate qa/ (CI: --base HEAD^1)
python scripts/qa/qa.py check --verbose           # also notes, e.g. a planned test that now exists
python scripts/qa/qa.py list --suite licence-activation --tag holder --tag semper
python scripts/qa/qa.py list --lane A             # the scenarios lane A runs
python scripts/qa/qa.py plan --run qa/runs/2026-11-licence-activation
python scripts/qa/qa.py report --run qa/runs/2026-11-licence-activation --compare qa/runs/<older run>
python -m unittest discover -s scripts/qa/tests -p "test_*.py"     # the tool's tests (pytest runs them too)
```

Exit codes: 0 fine; 1 `check` errors, or `report` found an unexplained fail;
2 a file could not be read or a run folder is malformed.
