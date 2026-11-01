#!/usr/bin/env python3
"""Capture a Firestore content manifest, or verify a restore against one.

`--emit` runs against the live database at export time and writes per-collection
document counts plus a small relationship sample. `--verify` runs against a
restored database and fails if it does not match.

This exists because a restore drill that only submits the import proves the API
accepted the job, not that the data came back. The failure mode that matters —
an import that "succeeds" and produces an empty or partial database — is
invisible without comparing against what was exported.
"""
from __future__ import annotations

import argparse
import json
import sys

from google.cloud import firestore

COLLECTIONS = (
    "users", "devices", "sessions", "files", "audit_logs",
    "licenses", "licenseInvites", "auth_links", "deleted_licenses",
    "deleted_sessions",
)
# Subcollections, counted across every parent as a collection group. Seats live
# under licenses/{id}/seats, so a top-level count would always read zero; a
# deleted licence's seats under deleted_licenses/{id}/deleted_seats.
GROUPS = ("seats", "deleted_seats")
# Challenges are single-use nonces with a 120s TTL: they legitimately differ
# between export and restore, so they are counted but never compared.
VOLATILE = ("challenges",)
SAMPLE_SIZE = 25


def _count(client, name: str) -> int:
    ref = client.collection_group(name) if name in GROUPS else client.collection(name)
    return ref.count().get()[0][0].value


def _relationship_sample(client) -> list[dict]:
    """A few session→files edges, so a restore that loses subcollection-shaped
    data is caught rather than passing on counts alone."""
    sample = []
    for doc in client.collection("sessions").order_by("__name__").limit(SAMPLE_SIZE).stream():
        body = doc.to_dict() or {}
        files = client.collection("files").where("sessionId", "==", doc.id).count().get()
        sample.append({
            "sessionId": doc.id,
            "uid": body.get("uid"),
            "fileCount": body.get("fileCount"),
            "actualFiles": files[0][0].value,
        })
    return sorted(sample, key=lambda s: s["sessionId"])


def emit(project: str) -> dict:
    client = firestore.Client(project=project)
    manifest = {
        "project": project,
        "counts": {name: _count(client, name) for name in COLLECTIONS + GROUPS},
        "volatileCounts": {name: _count(client, name) for name in VOLATILE},
        "sample": _relationship_sample(client),
    }
    return manifest


def verify(project: str, manifest: dict) -> list[str]:
    """Returns a list of human-readable failures; empty means the restore is good."""
    client = firestore.Client(project=project)
    failures = []

    for name, expected in manifest["counts"].items():
        actual = _count(client, name)
        if actual != expected:
            failures.append(f"{name}: expected {expected} documents, restored {actual}")
        elif expected == 0:
            print(f"  {name}: 0 (empty in the source too)")
        else:
            print(f"  {name}: {actual} ✓")

    # Counts alone would pass a restore that shuffled which files belong to which
    # session, so re-walk the recorded edges.
    for entry in manifest["sample"]:
        sid = entry["sessionId"]
        snap = client.collection("sessions").document(sid).get()
        if not snap.exists:
            failures.append(f"sample session {sid} is missing after restore")
            continue
        if (snap.to_dict() or {}).get("uid") != entry["uid"]:
            failures.append(f"sample session {sid} came back owned by a different uid")
        actual = client.collection("files").where("sessionId", "==", sid).count().get()[0][0].value
        if actual != entry["actualFiles"]:
            failures.append(
                f"sample session {sid}: expected {entry['actualFiles']} files, restored {actual}"
            )
    if manifest["sample"] and not failures:
        print(f"  relationship sample: {len(manifest['sample'])} sessions ✓")

    # A schemaVersion of 0/absent across the board means the restore predates the
    # migration that stamped it, i.e. the wrong export was imported.
    versioned = 0
    for doc in client.collection("users").order_by("__name__").limit(SAMPLE_SIZE).stream():
        if isinstance((doc.to_dict() or {}).get("schemaVersion"), int):
            versioned += 1
    if manifest["counts"].get("users", 0) > 0 and versioned == 0:
        failures.append("no sampled user document carries schemaVersion — wrong or stale export?")

    return failures


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--emit", metavar="PATH", help="write a manifest for this database")
    parser.add_argument("--verify", metavar="PATH", help="check this database against a manifest")
    args = parser.parse_args()

    if bool(args.emit) == bool(args.verify):
        raise SystemExit("pass exactly one of --emit or --verify")

    if args.emit:
        manifest = emit(args.project)
        with open(args.emit, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, indent=2, sort_keys=True)
        total = sum(manifest["counts"].values())
        print(f"Manifest written to {args.emit}: {total} documents across "
              f"{len(manifest['counts'])} collections")
        return

    with open(args.verify, encoding="utf-8") as handle:
        manifest = json.load(handle)
    print(f"Verifying {args.project} against {args.verify} "
          f"(exported from {manifest['project']})")
    failures = verify(args.project, manifest)
    if failures:
        print("\nRESTORE VERIFICATION FAILED:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        raise SystemExit(1)
    print("\nRestore verified: counts and sampled relationships match the export.")


if __name__ == "__main__":
    main()
