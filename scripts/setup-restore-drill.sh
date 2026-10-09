#!/usr/bin/env bash
# One-time setup for the Firestore restore drill (TD-156).
#
# Creates the throwaway drill project, its Firestore database and identity,
# gives that identity read access to the backup bucket, and sets the five
# variables of the `restore-drill` GitHub environment that
# .github/workflows/firestore-restore-drill.yml reads. The steps are the ones in
# docs/backend/FIRESTORE_DATA_PROTECTION.md ("Configuring the restore-drill
# environment"); each checks before it creates, so a rerun after a failure
# picks up where it stopped.
#
# Run by someone with project-creation and billing rights, owner on the
# production project's backup bucket, and admin on the GitHub repo:
#
#   scripts/setup-restore-drill.sh --billing-account 0X0X0X-0X0X0X-0X0X0X
#   scripts/setup-restore-drill.sh --dry-run      # print the steps, change nothing
#
# Nothing here grants the drill identity a role on production: it can read the
# backup bucket and nothing else there.
set -euo pipefail

DRILL=indicvision-dic-restore-drill
PROD=indicvision-dic-app
BUCKET=indicvision-dic-app-firestore-backups
LOCATION=asia-south1
REPO=sempermechanics/semperdic-app
POOL=projects/641964711637/locations/global/workloadIdentityPools/github
BILLING=""
DRY_RUN=0

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --billing-account) BILLING="${2:?--billing-account needs an id}"; shift 2 ;;
    --project) DRILL="${2:?--project needs an id}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage 2 ;;
  esac
done

if [ "$DRILL" = "$PROD" ]; then
  echo "refusing: the drill project must not be production ($PROD)" >&2
  exit 2
fi
if [ "$DRY_RUN" = 0 ] && [ -z "$BILLING" ]; then
  echo "--billing-account is required (gcloud billing accounts list)" >&2
  exit 2
fi

SA="restore-drill@${DRILL}.iam.gserviceaccount.com"

step() { printf '\n== %s\n' "$*"; }
run() {
  if [ "$DRY_RUN" = 1 ]; then
    printf '   would run: %s\n' "$*"
  else
    "$@"
  fi
}
have() { "$@" >/dev/null 2>&1; }

step "Drill project $DRILL"
if have gcloud projects describe "$DRILL"; then
  echo "   exists"
else
  run gcloud projects create "$DRILL"
fi

step "Billing (needed for the managed import; the drill stays within the free tier)"
if [ "$DRY_RUN" = 0 ] && [ "$(gcloud billing projects describe "$DRILL" --format='value(billingEnabled)')" = "True" ]; then
  echo "   linked"
else
  run gcloud billing projects link "$DRILL" --billing-account="${BILLING:-<account id>}"
fi

step "Firestore API and the (default) database in $LOCATION"
run gcloud services enable firestore.googleapis.com --project="$DRILL"
if have gcloud firestore databases describe --database='(default)' --project="$DRILL"; then
  echo "   database exists"
else
  run gcloud firestore databases create --location="$LOCATION" --type=firestore-native --project="$DRILL"
fi

step "Drill identity $SA: Firestore owner on the drill project only"
if have gcloud iam service-accounts describe "$SA" --project="$DRILL"; then
  echo "   exists"
else
  run gcloud iam service-accounts create restore-drill --project="$DRILL" \
    --display-name="Firestore restore drill"
fi
run gcloud projects add-iam-policy-binding "$DRILL" --member="serviceAccount:$SA" \
  --role=roles/datastore.owner --condition=None --quiet
run gcloud iam service-accounts add-iam-policy-binding "$SA" --project="$DRILL" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/$POOL/attribute.repository/$REPO" --quiet

step "Read-only on the exports, for the drill identity and the drill's Firestore agent"
run gcloud beta services identity create --service=firestore.googleapis.com --project="$DRILL"
if [ "$DRY_RUN" = 1 ]; then
  DRILL_NUM="<drill project number>"
else
  DRILL_NUM=$(gcloud projects describe "$DRILL" --format='value(projectNumber)')
fi
for member in "serviceAccount:$SA" \
              "serviceAccount:service-${DRILL_NUM}@gcp-sa-firestore.iam.gserviceaccount.com"; do
  run gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" \
    --member="$member" --role=roles/storage.objectViewer --quiet
done

step "Variables of the restore-drill environment"
for kv in "GCP_WORKLOAD_IDENTITY_PROVIDER=$POOL/providers/github" \
          "FIRESTORE_RESTORE_DRILL_SERVICE_ACCOUNT=$SA" \
          "FIRESTORE_RESTORE_DRILL_PROJECT=$DRILL" \
          "FIRESTORE_BACKUP_BUCKET=$BUCKET" \
          "GCP_PROJECT=$PROD"; do
  run gh variable set "${kv%%=*}" --env restore-drill --body "${kv#*=}" -R "$REPO"
done

step "Done"
echo "   First drill: gh workflow run firestore-restore-drill.yml -R $REPO"
echo "   Record the RTO it prints in docs/ops/PRODUCTION_READINESS_GATE.md."
