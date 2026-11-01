#!/usr/bin/env bash
# Deploy the Firestore security rules and/or composite indexes.
#
# Usage (from anywhere; Git Bash on Windows):
#   ./scripts/deploy-firestore.sh            # rules and indexes
#   ./scripts/deploy-firestore.sh rules      # firestore.rules only
#   ./scripts/deploy-firestore.sh indexes    # backend/firestore.indexes.json only
#
# Optional: PROJECT=<id> (default indicvision-dic-app, the backend project that
# holds Firestore; not firebase-hosting/'s default, the Auth project).
#
# Runs a pinned Firebase CLI through npx (FIREBASE_TOOLS_VERSION below), never
# whichever `firebase` is first on PATH: needs Node >= 20 and npm. From
# PowerShell, `bash` may resolve to WSL (Node 12 there); run this from Git Bash.
#
# Why pinned (TD-316): every live composite index is stored with
# `density: SPARSE_ALL` and a trailing `__name__` field in the direction of its
# last field, which the indexes file leaves implicit. The CLI must normalise one
# side to the other. 13.x drops the live `__name__`; 15.x keeps it and appends
# the implicit one to each file entry (and defaults density to SPARSE_ALL); 14.11.0
# keeps the live `__name__` without appending it, so it matches none of them,
# calls them "not present in your firestore indexes file" and re-creates the
# first (`sessions`), which Firestore refuses with HTTP 409 (2026-10-22). Before
# changing the version, list the live indexes with it (read-only) and check the
# file still matches:
#   npx --yes firebase-tools@<ver> firestore:indexes --project indicvision-dic-app --database '(default)'
#
# The Firebase CLI refuses any file outside the directory holding its
# firebase.json, and neither file lives under one, so both are staged in a
# scratch directory with a firebase.json of their own.
#
# The indexes file also declares the TTL policies (challenges.expireAt,
# deleted_licenses.purgeAt, deleted_seats.purgeAt, audit_logs.expireAt,
# deleted_sessions.expireAt) as
# fieldOverrides; a deploy creates any that are missing, so audit_logs.expireAt
# waits until the TD-246 backfill has run (DEPLOY_CHECKLIST_2026-10.md §6). The CLI
# counts each live TTL policy as a field override and refuses, in
# non-interactive mode, to leave one that the file omits. Never add --force
# here; it deletes every live override the file does not declare, TTL included.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIREBASE_TOOLS_VERSION="15.33.0"
PROJECT="${PROJECT:-indicvision-dic-app}"
case "${1:-all}" in
  rules) ONLY="firestore:rules" ;;
  indexes) ONLY="firestore:indexes" ;;
  all) ONLY="firestore" ;;
  *) echo "usage: $0 [rules|indexes]" >&2; exit 2 ;;
esac

# Every client read and write is refused; the backend's server SDK bypasses
# rules. Refuse to publish anything else by accident.
if ! grep -q 'allow read, write: if false;' "${ROOT}/firestore.rules"; then
  echo "firestore.rules is not deny-all; not deploying it" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT
cp "${ROOT}/firestore.rules" "${STAGE}/firestore.rules"
cp "${ROOT}/backend/firestore.indexes.json" "${STAGE}/firestore.indexes.json"
printf '{"firestore":{"rules":"firestore.rules","indexes":"firestore.indexes.json"}}\n' \
  > "${STAGE}/firebase.json"

echo "Deploying ${ONLY} to ${PROJECT}"
echo "Firebase CLI: firebase-tools@${FIREBASE_TOOLS_VERSION}"
(cd "${STAGE}" && npx --yes "firebase-tools@${FIREBASE_TOOLS_VERSION}" \
  deploy --only "${ONLY}" --project "${PROJECT}" --non-interactive)
