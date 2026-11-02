#!/usr/bin/env bash
# Empty the restore-drill database after a drill (firestore-restore-drill.yml).
#
# The drill project holds a full copy of production data once the import ran.
# Left populated between monthly runs it would be a second copy of everything,
# in a project with weaker access controls than production.
#
# Usage: RESTORE_DRILL_PROJECT=<drill> PRODUCTION_PROJECT=<prod> \
#          bash scripts/firestore-drill-purge.sh
#
# Refuses (exit 2) an empty project or the production one. Bulk-deletes every
# collection id in PURGE_COLLECTIONS below, trying all of them, and exits 1
# naming each one whose bulk-delete failed: a purge that "passed" with data left
# behind used to print a line and carry on (review finding L9-5, 2026-11-01).
set -euo pipefail

: "${RESTORE_DRILL_PROJECT:?set RESTORE_DRILL_PROJECT (the drill project)}"
: "${PRODUCTION_PROJECT:?set PRODUCTION_PROJECT as a safety check}"

if [[ "${RESTORE_DRILL_PROJECT}" == "${PRODUCTION_PROJECT}" ]]; then
  echo "::error::Drill project equals production; refusing to purge."
  exit 2
fi

# Every collection the backend writes (grep `collection(` in backend/app),
# plus the subcollections. bulk-delete takes collection-group ids, so "seats"
# reaches licenses/*/seats, "activations" licenses/*/activations (ADR-033) and
# "deleted_seats" deleted_licenses/*/deleted_seats.
# scripts/tests/test_ci_deploy_guards.py holds this list to backend/app and
# to the collections scripts/firestore_verify.py counts.
PURGE_COLLECTIONS=(
  users devices sessions files audit_logs challenges _migrations
  licenses seats activations licenseInvites auth_links
  deleted_licenses deleted_seats deleted_sessions
)

failed=()
for c in "${PURGE_COLLECTIONS[@]}"; do
  if gcloud firestore bulk-delete \
      --collection-ids="$c" \
      --project="${RESTORE_DRILL_PROJECT}" \
      --quiet; then
    echo "bulk-delete of $c submitted"
  else
    echo "::error::bulk-delete of $c failed"
    failed+=("$c")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "::error::The drill database in ${RESTORE_DRILL_PROJECT} may still hold data in: ${failed[*]}. Purge them by hand (gcloud firestore bulk-delete --collection-ids=...)."
  exit 1
fi
echo "Purge submitted for ${#PURGE_COLLECTIONS[@]} collection ids in ${RESTORE_DRILL_PROJECT}."
