#!/usr/bin/env bash
# Refuse a Cloud Run service or API Gateway name that does not belong to the
# environment a deploy-backend.yml run was dispatched for.
#
# Usage: scripts/check_deploy_target.sh ENVIRONMENT NAME [NAME ...]
#
# ENVIRONMENT is the workflow's `environment` input, `staging` or `production`.
# Each NAME (the service, the gateway id, the gateway API) must be a valid
# resource name and:
#   staging     → end in `-staging` (semper-api-staging, semper-gw-staging);
#   production  → not end in `-staging` (semper-api, semper-gw).
#
# Why: both GitHub environments authenticate with the same repo-level WIF
# provider and deploy SA, and only `production` has protection rules (main
# only, the owner's review). Without this, environment=staging with
# service=semper-api deployed any branch to production unreviewed (review
# finding L9-1, 2026-11-01). Prints one `::error::` line per bad name and
# exits 1; exits 2 on a usage error. Reads nothing and calls nothing.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 ENVIRONMENT NAME [NAME ...]" >&2
  exit 2
fi

env_name="$1"
shift
case "$env_name" in
  staging|production) ;;
  *)
    echo "::error::unknown environment '${env_name}' (expected staging or production)"
    exit 1
    ;;
esac

bad=0
for name in "$@"; do
  # Cloud Run and API Gateway ids: lowercase letters, digits and dashes,
  # starting with a letter, not ending in a dash, at most 63 characters.
  if ! [[ "$name" =~ ^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$ ]]; then
    echo "::error::'${name}' is not a valid Cloud Run / API Gateway name"
    bad=1
    continue
  fi
  if [ "$env_name" = "staging" ] && [[ "$name" != *-staging ]]; then
    echo "::error::environment=staging may only deploy a *-staging target, not '${name}'. Production names deploy only with environment=production, which requires main and the owner's review."
    bad=1
  elif [ "$env_name" = "production" ] && [[ "$name" == *-staging ]]; then
    echo "::error::environment=production must not target the staging name '${name}'."
    bad=1
  fi
done

if [ "$bad" -ne 0 ]; then
  exit 1
fi
echo "Deploy targets fit environment=${env_name}: $*"
