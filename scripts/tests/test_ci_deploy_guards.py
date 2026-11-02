"""The deploy and CI guards of the 2026-11-01 review (L9-1, L9-2, L9-4, L9-5,
L9-6 / L8-9, L9-8).

- `scripts/check_deploy_target.sh` and the deploy-backend.yml steps that call
  it: a staging run cannot deploy the production service or gateway (L9-1).
- ci.yml's tier-flag step: the Dependabot cheap path needs Dependabot as the
  actor, not just a `dependabot/` branch name (L9-2); Tier 3's report needs a
  test to have run (L9-4); the R8 mapping artifacts are short-lived (L9-8).
- `scripts/firestore-drill-purge.sh`: purges `activations` too, tries every
  collection and then fails on any failed bulk-delete (L9-5).
- `scripts/deploy-console.sh`: refuses a dirty tree, another branch and a
  non-https API origin, runs the console and legal checks, and deploys the
  pinned legal pages with LEGAL_PAGES_FROM (L9-6 / L8-9), all against stubs:
  `firebase` and `gcloud` here are shell scripts that log, nothing deploys.

The workflow steps are run from their own `run:` text, so a test fails when
the step changes, not only when a copy of it does. Stdlib only:
`python -m unittest discover -s scripts/tests`.
"""
from __future__ import annotations

import ast
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[2]
_WF = _ROOT / ".github" / "workflows"
_TARGET = _ROOT / "scripts" / "check_deploy_target.sh"
_PURGE = _ROOT / "scripts" / "firestore-drill-purge.sh"
_CONSOLE = _ROOT / "scripts" / "deploy-console.sh"

_POSIX = os.name != "nt" and shutil.which("bash") is not None and shutil.which("git") is not None
needs_bash = unittest.skipUnless(_POSIX, "needs a POSIX bash and git (runs in CI on ubuntu)")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _run_block(text: str, anchor: str) -> str:
    """The `run: |` block of the first step after [anchor], dedented."""
    start = text.index(anchor)
    m = re.compile(r"^( *)run: \|[^\n]*\n", re.MULTILINE).search(text, start)
    assert m, f"no run block after {anchor!r}"
    indent = len(m.group(1))
    lines = []
    for line in text[m.end():].splitlines():
        if line.strip() and len(line) - len(line.lstrip(" ")) <= indent:
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines)) + "\n"


def _job(text: str, job: str) -> str:
    """The text of one job of a workflow (from `  job:` to the next job)."""
    m = re.search(rf"^  {re.escape(job)}:\n", text, re.MULTILINE)
    assert m, f"no job {job}"
    nxt = re.compile(r"^  [a-z0-9][a-z0-9_-]*:\n", re.MULTILINE).search(text, m.end())
    return text[m.start(): nxt.start() if nxt else len(text)]


def _bash(script: str | Path, *args: str, env: dict | None = None, cwd: Path | None = None,
          ) -> subprocess.CompletedProcess:
    cmd = ["bash", str(script), *args] if isinstance(script, Path) else ["bash", "-c", script, "step", *args]
    full = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "/tmp")}
    full.update(env or {})
    return subprocess.run(cmd, capture_output=True, text=True, env=full, cwd=cwd or _ROOT, timeout=120)  # noqa: S603 - bash and this repo's own scripts


def _outputs(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)


# --- L9-1: deploy-backend.yml service and gateway vs environment ------------------

@needs_bash
class DeployTargetTest(unittest.TestCase):
    def check(self, *args: str) -> subprocess.CompletedProcess:
        return _bash(_TARGET, *args)

    def test_each_environment_takes_its_own_names(self):
        self.assertEqual(self.check("staging", "semper-api-staging").returncode, 0)
        self.assertEqual(self.check("staging", "semper-api-staging", "semper-gw-staging",
                                    "semper-api-staging").returncode, 0)
        self.assertEqual(self.check("production", "semper-api").returncode, 0)
        self.assertEqual(self.check("production", "semper-api", "semper-gw", "semper-api").returncode, 0)

    def test_staging_cannot_reach_a_production_name(self):
        for name in ("semper-api", "semper-gw", "semper-api-staging-old", "anything"):
            proc = self.check("staging", name)
            self.assertEqual(proc.returncode, 1, name)
            self.assertIn(f"not '{name}'", proc.stdout)
        # One bad name among good ones is enough.
        proc = self.check("staging", "semper-api-staging", "semper-gw", "semper-api-staging")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("'semper-gw'", proc.stdout)

    def test_production_refuses_a_staging_name(self):
        proc = self.check("production", "semper-api-staging")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("::error::environment=production must not target", proc.stdout)

    def test_an_invalid_name_or_environment_is_refused(self):
        for name in ("Semper-api-staging", "semper_api-staging", "x-staging;id", "-staging", ""):
            self.assertEqual(self.check("staging", name).returncode, 1, repr(name))
        self.assertEqual(self.check("prod", "semper-api").returncode, 1)
        self.assertEqual(self.check("staging").returncode, 2)

    def _meta(self, service: str, environment: str) -> tuple[subprocess.CompletedProcess, dict]:
        """The deploy job's own `Resolve service name` step."""
        step = _run_block(_read(_WF / "deploy-backend.yml"), "- id: meta")
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            proc = _bash(step, env={"SVC_INPUT": service, "TARGET_ENV": environment, "GITHUB_OUTPUT": str(out)})
            return proc, _outputs(out)

    def test_the_deploy_step_resolves_and_checks_the_service(self):
        proc, out = self._meta("", "staging")
        self.assertEqual((proc.returncode, out.get("service")), (0, "semper-api-staging"), proc.stdout + proc.stderr)
        proc, out = self._meta("", "production")
        self.assertEqual((proc.returncode, out.get("service")), (0, "semper-api"))
        # The finding: environment=staging, service=semper-api, any branch.
        proc, out = self._meta("semper-api", "staging")
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("service", out)
        proc, out = self._meta("semper-api-staging", "production")
        self.assertNotEqual(proc.returncode, 0)

    def test_both_jobs_check_before_they_authenticate(self):
        text = _read(_WF / "deploy-backend.yml")
        deploy, gateway = _job(text, "deploy"), _job(text, "gateway")
        self.assertLess(deploy.index('bash scripts/check_deploy_target.sh "${TARGET_ENV}" "$SVC"'),
                        deploy.index("- id: auth"))
        check = gateway.index('bash scripts/check_deploy_target.sh "$TARGET_ENV" "$SERVICE" "$GW_ID" "$GW_API"')
        self.assertLess(check, gateway.index("google-github-actions/auth@"))
        self.assertIn("TARGET_ENV: ${{ github.event.inputs.environment }}", gateway)


# --- L9-2, L9-4, L9-8: ci.yml and release.yml ----------------------------------------

@needs_bash
class TierFlagsTest(unittest.TestCase):
    def flags(self, **env: str) -> dict[str, str]:
        step = _run_block(_read(_WF / "ci.yml"), "name: Resolve tier flags")
        base = {"EVENT_NAME": "pull_request", "REF": "refs/pull/1/merge", "BEFORE": "",
                "FULL_DISPATCH": "false", "FULL_LABEL": "false", "E2E_LABEL": "false",
                "RELEASE_LABEL": "false", "BENCHMARK_LABEL": "false", "BENCHMARK_DISPATCH": "false"}
        for name in ("APP", "NATIVE_JNI", "NATIVE_CI", "BACKEND", "RULES", "CI_WORKFLOW",
                     "ANDROID_TEST", "PACKAGING", "HOT_PATH", "SAST"):
            base[f"FILTER_{name}"] = "false"
        base.update(env)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            base["GITHUB_OUTPUT"] = str(out)
            proc = _bash(step, env=base)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            return _outputs(out)

    def test_dependabot_itself_gets_the_cheap_path(self):
        out = self.flags(HEAD_REF="dependabot/github_actions/actions/checkout-7", ACTOR="dependabot[bot]",
                         FILTER_APP="true", FILTER_CI_WORKFLOW="true")
        self.assertEqual(out["app"], "false")
        self.assertEqual(out["backend"], "false")
        out = self.flags(HEAD_REF="dependabot/pip/backend/x", ACTOR="dependabot[bot]")
        self.assertEqual(out["backend"], "true")

    def test_a_dependabot_named_branch_from_anyone_else_takes_path_mode(self):
        out = self.flags(HEAD_REF="dependabot/github_actions/x", ACTOR="someone",
                         FILTER_APP="true", FILTER_NATIVE_JNI="true")
        self.assertEqual(out["app"], "true")
        self.assertEqual(out["emulator"], "true")
        self.assertEqual(out["release_build"], "true")

    def test_tier3_report_requires_a_test(self):
        tier3 = _job(_read(_WF / "ci.yml"), "tier3-emulator-e2e")
        self.assertRegex(tier3, r"scripts/ci_test_report\.py --require-tests app/build/outputs/androidTest-results")


class MappingArtifactTest(unittest.TestCase):
    def test_r8_mappings_are_kept_briefly(self):
        """A public repository's artifacts are readable by any signed-in user."""
        for wf, most in (("ci.yml", 7), ("release.yml", 30)):
            text = _read(_WF / wf)
            step = text[text.index("- name: Upload R8 mapping"):]
            step = step[: step.index("\n\n")]
            days = re.search(r"retention-days: (\d+)", step)
            self.assertIsNotNone(days, wf)
            self.assertLessEqual(int(days.group(1)), most, wf)
        self.assertNotIn("kept private", _read(_WF / "release.yml"))


# --- L9-5: the restore drill's purge -------------------------------------------------

def _purge_list() -> list[str]:
    body = re.search(r"PURGE_COLLECTIONS=\(\n(.*?)\n\)", _read(_PURGE), re.DOTALL).group(1)
    return body.split()


def _verify_names() -> set[str]:
    tree = ast.parse(_read(_ROOT / "scripts" / "firestore_verify.py"))
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", "") in ("COLLECTIONS", "GROUPS", "VOLATILE")
                                                for t in node.targets):
            names |= set(ast.literal_eval(node.value))
    return names


def _backend_collections() -> set[str]:
    """Every collection id backend/app names: literal `collection("x")` /
    `collection_group("x")`, and module constants passed to `collection(NAME)`."""
    names: set[str] = set()
    for path in (_ROOT / "backend" / "app").rglob("*.py"):
        text = _read(path)
        names |= set(re.findall(r"collection(?:_group)?\(\s*[\"']([A-Za-z_]+)[\"']", text))
        consts = dict(re.findall(r"^([A-Z_]+)\s*=\s*[\"']([A-Za-z_]+)[\"']", text, re.MULTILINE))
        for const in re.findall(r"collection(?:_group)?\(\s*([A-Z_]+)\s*\)", text):
            if const in consts:
                names.add(consts[const])
    return names


class PurgeCoverageTest(unittest.TestCase):
    def test_the_purge_covers_every_backend_collection(self):
        backend = _backend_collections()
        self.assertIn("activations", backend)  # the parser still sees ADR-033's subcollection
        self.assertEqual(backend - set(_purge_list()), set())

    def test_the_purge_covers_every_verified_collection(self):
        self.assertIn("activations", _verify_names())
        self.assertEqual(_verify_names() - set(_purge_list()), set())

    def test_the_workflow_runs_the_script_and_swallows_nothing(self):
        drill = _read(_WF / "firestore-restore-drill.yml")
        self.assertIn("run: bash scripts/firestore-drill-purge.sh", drill)
        self.assertNotIn("gcloud firestore bulk-delete", drill)  # no inline copy of the loop
        self.assertNotIn("reported a problem", drill)


@needs_bash
class PurgeRunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        bin_dir = self.tmp / "bin"
        bin_dir.mkdir()
        self.log = self.tmp / "gcloud.log"
        stub = bin_dir / "gcloud"
        # Logs each call; fails the bulk-delete of every id in $STUB_FAIL.
        stub.write_text(textwrap.dedent("""\
            #!/usr/bin/env bash
            echo "$*" >> "$STUB_LOG"
            for a in "$@"; do
              case "$a" in --collection-ids=*)
                id="${a#--collection-ids=}"
                for f in $STUB_FAIL; do [ "$f" = "$id" ] && { echo "ERROR: $id" >&2; exit 1; }; done ;;
              esac
            done
            exit 0
            """), encoding="utf-8")
        stub.chmod(0o755)
        self.path = f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}"

    def purge(self, drill: str, prod: str, fail: str = "") -> tuple[subprocess.CompletedProcess, list[str]]:
        env = {"PATH": self.path, "STUB_LOG": str(self.log), "STUB_FAIL": fail}
        if drill is not None:
            env["RESTORE_DRILL_PROJECT"] = drill
        if prod is not None:
            env["PRODUCTION_PROJECT"] = prod
        proc = _bash(_PURGE, env=env)
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        return proc, calls

    def test_every_collection_is_purged_in_the_drill_project(self):
        proc, calls = self.purge("drill-proj", "prod-proj")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        ids = [re.search(r"--collection-ids=(\S+)", c).group(1) for c in calls]
        self.assertEqual(ids, _purge_list())
        self.assertIn("activations", ids)
        self.assertTrue(all("--project=drill-proj" in c and "firestore bulk-delete" in c for c in calls))

    def test_a_failed_delete_fails_the_step_after_trying_the_rest(self):
        proc, calls = self.purge("drill-proj", "prod-proj", fail="seats deleted_seats")
        self.assertEqual(proc.returncode, 1)
        self.assertEqual(len(calls), len(_purge_list()))  # carried on past the failure
        self.assertIn("may still hold data in: seats deleted_seats", proc.stdout)

    def test_the_production_project_or_none_is_refused_untouched(self):
        proc, calls = self.purge("prod-proj", "prod-proj")
        self.assertEqual(proc.returncode, 2)
        self.assertIn("refusing to purge", proc.stdout)
        self.assertEqual(calls, [])
        for drill, prod in (("", "prod"), (None, "prod"), ("drill", None)):
            proc, calls = self.purge(drill, prod)
            self.assertNotEqual(proc.returncode, 0)
            self.assertEqual(calls, [])


# --- L9-6 / L8-9: deploy-console.sh ---------------------------------------------------

_STUB_PY = """\
import sys, os
with open(os.environ["STUB_LOG"], "a", encoding="utf-8") as fh:
    fh.write("{name} " + " ".join(sys.argv[1:]) + "\\n")
sys.exit(int(os.environ.get("STUB_{env}_EXIT", "0")))
"""


@needs_bash
class DeployConsoleTest(unittest.TestCase):
    """A small repository shaped like this one: the real deploy-console.sh,
    stub check scripts, a stub `firebase` that snapshots what it would ship."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / "repo"
        self.log = self.tmp / "calls.log"
        self.snap = self.tmp / "shipped"
        self.genv = {
            "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.test",
            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.test",
            "GIT_CONFIG_NOSYSTEM": "1", "HOME": str(self.tmp),
        }
        scripts = self.repo / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy(_CONSOLE, scripts / "deploy-console.sh")
        for name, env in (("check_console", "CHECK_CONSOLE"), ("render_legal_pages", "RENDER"),
                          ("check_live_console", "LIVE")):
            (scripts / f"{name}.py").write_text(_STUB_PY.format(name=name, env=env), encoding="utf-8")
        public = self.repo / "firebase-hosting" / "public"
        (public / "console").mkdir(parents=True)
        (public / "console" / "config.js").write_text('export const API_BASE_URL = "__API_BASE_URL__";\n',
                                                      encoding="utf-8")
        (self.repo / "firebase-hosting" / "firebase.json").write_text(
            '{"hosting": {"connect": "\'self\' __API_ORIGIN__"}}\n', encoding="utf-8")
        self._legal("cleared privacy\n", "cleared terms\n")
        self._git("init", "-q")
        self._git("symbolic-ref", "HEAD", "refs/heads/main")
        self._git("add", "-A")
        self._git("commit", "-qm", "cleared legal pages")
        self.cleared = self._git("rev-parse", "HEAD").stdout.strip()
        self._legal("uncleared privacy\n", "uncleared terms\n")
        self._git("commit", "-qam", "legal text counsel has not cleared")

        bin_dir = self.tmp / "bin"
        bin_dir.mkdir()
        firebase = bin_dir / "firebase"
        firebase.write_text(textwrap.dedent("""\
            #!/usr/bin/env bash
            echo "firebase $* (in $(basename "$PWD"))" >> "$STUB_LOG"
            rm -rf "$STUB_SNAP"; mkdir -p "$STUB_SNAP"
            cp -R public firebase.json "$STUB_SNAP/"
            """), encoding="utf-8")
        firebase.chmod(0o755)
        self.path = f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}"

    def _legal(self, privacy: str, terms: str) -> None:
        public = self.repo / "firebase-hosting" / "public"
        for page, body in (("privacy", privacy), ("terms", terms)):
            (public / page).mkdir(parents=True, exist_ok=True)
            (public / page / "index.html").write_text(body, encoding="utf-8")

    def _git(self, *args: str) -> subprocess.CompletedProcess:
        env = {"PATH": os.environ.get("PATH", ""), **self.genv}
        proc = subprocess.run(["git", *args], cwd=self.repo, capture_output=True, text=True, env=env, timeout=60)  # noqa: S603,S607
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc

    def deploy(self, **env: str) -> tuple[subprocess.CompletedProcess, list[str]]:
        full = {"PATH": self.path, "STUB_LOG": str(self.log), "STUB_SNAP": str(self.snap), **self.genv,
                "API_BASE_URL": "https://gw.example.test"}
        full.update(env)
        proc = _bash(self.repo / "scripts" / "deploy-console.sh", env=full, cwd=self.repo)
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        return proc, calls

    def shipped(self, rel: str) -> str:
        return (self.snap / rel).read_text(encoding="utf-8")

    def assert_restored(self) -> None:
        self.assertEqual(self._git("status", "--porcelain", "--ignored").stdout, "")
        self.assertIn("__API_BASE_URL__", _read(self.repo / "firebase-hosting/public/console/config.js"))

    def test_a_clean_main_deploys_after_both_checks(self):
        proc, calls = self.deploy(API_BASE_URL="https://gw.example.test//")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual([c.split()[0] for c in calls],
                         ["check_console", "render_legal_pages", "firebase", "check_live_console"])
        self.assertIn("render_legal_pages --check", calls)
        self.assertIn("firebase deploy --only hosting (in firebase-hosting)", calls)
        # The trailing slashes are gone everywhere the origin lands.
        self.assertIn('"https://gw.example.test";', self.shipped("public/console/config.js"))
        self.assertIn("'self' https://gw.example.test\"", self.shipped("firebase.json"))
        self.assertTrue(calls[-1].endswith("--api https://gw.example.test"), calls[-1])
        # main's legal pages, and no backup published beside the pages.
        self.assertEqual(self.shipped("public/privacy/index.html"), "uncleared privacy\n")
        shipped = sorted(str(p.relative_to(self.snap / "public")) for p in (self.snap / "public").rglob("*")
                         if p.is_file())
        self.assertEqual(shipped, ["console/config.js", "privacy/index.html", "terms/index.html"])
        self.assert_restored()

    def test_the_legal_pin_ships_the_cleared_pages_and_skips_the_render_check(self):
        proc, calls = self.deploy(LEGAL_PAGES_FROM=self.cleared[:8])
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual([c.split()[0] for c in calls], ["check_console", "firebase", "check_live_console"])
        self.assertEqual(self.shipped("public/privacy/index.html"), "cleared privacy\n")
        self.assertEqual(self.shipped("public/terms/index.html"), "cleared terms\n")
        self.assertIn("render_legal_pages.py --check skipped", proc.stdout)
        self.assert_restored()
        self.assertEqual(_read(self.repo / "firebase-hosting/public/terms/index.html"), "uncleared terms\n")

    def test_a_legal_pin_that_is_no_commit_is_refused(self):
        proc, calls = self.deploy(LEGAL_PAGES_FROM="0000000")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("is not a commit", proc.stderr)
        self.assertEqual(calls, [])

    def test_a_dirty_tree_is_refused_before_any_check(self):
        (self.repo / "firebase-hosting/public/terms/index.html").write_text("edited\n", encoding="utf-8")
        proc, calls = self.deploy()
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Refusing to deploy: the tree is not clean", proc.stderr)
        self.assertIn("firebase-hosting/public/terms/index.html", proc.stderr)
        self.assertEqual(calls, [])

    def test_an_untracked_file_is_refused_only_where_it_would_be_published(self):
        (self.repo / "notes.txt").write_text("x", encoding="utf-8")
        proc, _ = self.deploy(DRY_RUN="1")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        (self.repo / "firebase-hosting/public/stray.html").write_text("x", encoding="utf-8")
        proc, calls = self.deploy()
        self.assertEqual(proc.returncode, 1)
        self.assertIn("?? firebase-hosting/public/stray.html", proc.stderr)
        self.assertNotIn("firebase", " ".join(calls))

    def test_another_branch_is_refused_unless_overridden(self):
        self._git("checkout", "-qb", "feature")
        proc, calls = self.deploy()
        self.assertEqual(proc.returncode, 1)
        self.assertIn("on 'feature', not main", proc.stderr)
        self.assertEqual(calls, [])
        proc, calls = self.deploy(DEPLOY_CONSOLE_ALLOW_UNCLEAN="1")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("WARNING (DEPLOY_CONSOLE_ALLOW_UNCLEAN=1)", proc.stderr)
        self.assertIn("firebase deploy --only hosting (in firebase-hosting)", calls)

    def test_an_api_base_url_that_is_not_an_https_origin_is_refused(self):
        for url in ("http://gw.example.test", "https://gw.example.test/v1", "gw.example.test",
                    "https://gw.example.test extra"):
            proc, calls = self.deploy(API_BASE_URL=url)
            self.assertEqual(proc.returncode, 1, url)
            self.assertIn("must be an https:// origin", proc.stderr)
            self.assertEqual(calls, [])

    def test_a_failing_console_check_stops_the_deploy(self):
        proc, calls = self.deploy(STUB_CHECK_CONSOLE_EXIT="1")
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(calls, ["check_console "])
        self.assert_restored()

    def test_a_dry_run_stops_before_firebase(self):
        proc, calls = self.deploy(DRY_RUN="1", LEGAL_PAGES_FROM=self.cleared)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("stopping before firebase deploy", proc.stdout)
        self.assertEqual(calls, ["check_console "])
        self.assert_restored()


if __name__ == "__main__":
    unittest.main()
