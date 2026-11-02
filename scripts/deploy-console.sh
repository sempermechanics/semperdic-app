#!/usr/bin/env bash
# Substitute console placeholders, deploy Hosting, always restore templates.
#
# Usage (from repo root or firebase-hosting/), on a clean `main`:
#   API_BASE_URL=https://your-gateway-host ./scripts/deploy-console.sh
#
# There is no AUTH_DOMAIN: auth.js uses the page's own host as authDomain.
#
# Before anything is deployed the script refuses (review findings L9-6 / L8-9,
# 2026-11-01):
#   - a branch other than `main`, a tracked file changed anywhere, or an
#     untracked or ignored file under firebase-hosting/public (it would be
#     published): every Hosting deploy replaces the whole site, so it must be
#     exactly what `main` holds;
#   - an API_BASE_URL that is not an https:// origin (trailing slashes are
#     stripped first: the CSP and check_live_console.py compare it exactly);
#   - a console page that fails scripts/check_console.py, or legal pages that
#     do not match docs/legal/ (scripts/render_legal_pages.py --check).
#
# Optional: LEGAL_PAGES_FROM=<commit> deploys firebase-hosting/public/privacy/
#           and terms/index.html as that commit holds them, and skips the
#           render check (they are not rendered from main's docs/legal/). The
#           worktree copies are swapped in for the deploy and restored after,
#           like the placeholders. DEPLOY_CHECKLIST_2026-10.md §4 / TD-277:
#           LEGAL_PAGES_FROM=b940cc29, the last pages counsel cleared.
# Optional: DEPLOY_CONSOLE_ALLOW_UNCLEAN=1 deploys from another branch or a
#           dirty tree anyway, with a warning (a preview of a branch, say).
# Optional: DRY_RUN=1 runs every check and the substitution, then stops before
#           `firebase deploy` and restores the templates.
# Optional: FIREBASE_PROJECT=indicvision-dic-app-auth
# Optional: LIVE_ORIGIN=https://app.sempermechanics.com (the host checked after
#           the deploy; scripts/check_live_console.py)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOSTING="${ROOT}/firebase-hosting"
CFG="${HOSTING}/public/console/config.js"
JSON="${HOSTING}/firebase.json"
LEGAL_PAGES=(
  "firebase-hosting/public/privacy/index.html"
  "firebase-hosting/public/terms/index.html"
)

: "${API_BASE_URL:?set API_BASE_URL to the API Gateway origin (https://host)}"
while [[ "${API_BASE_URL}" == */ ]]; do
  API_BASE_URL="${API_BASE_URL%/}"
done
if ! [[ "${API_BASE_URL}" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]]; then
  echo "API_BASE_URL must be an https:// origin with no path (got '${API_BASE_URL}')." >&2
  exit 1
fi

# The tree being deployed is the tree on main.
problems=()
branch="$(git -C "${ROOT}" rev-parse --abbrev-ref HEAD)"
if [[ "${branch}" != "main" ]]; then
  problems+=("the checkout is on '${branch}', not main")
fi
changed="$(git -C "${ROOT}" status --porcelain --untracked-files=no)"
published="$(git -C "${ROOT}" status --porcelain --untracked-files=all --ignored -- firebase-hosting/public)"
if [[ -n "${changed}${published}" ]]; then
  problems+=("the tree is not clean:"$'\n'"$(printf '%s\n%s\n' "${changed}" "${published}" | sed '/^$/d' | sort -u)")
fi
if [[ ${#problems[@]} -gt 0 ]]; then
  if [[ "${DEPLOY_CONSOLE_ALLOW_UNCLEAN:-}" == "1" ]]; then
    printf 'WARNING (DEPLOY_CONSOLE_ALLOW_UNCLEAN=1): %s\n' "${problems[@]}" >&2
  else
    printf 'Refusing to deploy: %s\n' "${problems[@]}" >&2
    echo "Deploy from a clean main checkout, or set DEPLOY_CONSOLE_ALLOW_UNCLEAN=1 on purpose." >&2
    exit 1
  fi
fi

LEGAL_REV=""
if [[ -n "${LEGAL_PAGES_FROM:-}" ]]; then
  if ! LEGAL_REV="$(git -C "${ROOT}" rev-parse --verify --quiet "${LEGAL_PAGES_FROM}^{commit}")"; then
    echo "LEGAL_PAGES_FROM='${LEGAL_PAGES_FROM}' is not a commit in this repository." >&2
    exit 1
  fi
  for page in "${LEGAL_PAGES[@]}"; do
    if ! git -C "${ROOT}" cat-file -e "${LEGAL_REV}:${page}" 2>/dev/null; then
      echo "LEGAL_PAGES_FROM: ${LEGAL_PAGES_FROM} has no ${page}." >&2
      exit 1
    fi
  done
fi

# The first Python 3 that actually runs. On Windows `python3` (and often
# `python`) is the Microsoft Store alias: it exists on PATH but only prints an
# install hint, so probe by running each candidate rather than by lookup.
PYTHON=()
for candidate in "python3" "python" "py -3"; do
  read -r -a cmd <<< "${candidate}"
  if "${cmd[@]}" -c 'import sys; sys.exit(sys.version_info < (3,))' >/dev/null 2>&1; then
    PYTHON=("${cmd[@]}")
    break
  fi
done
if [[ ${#PYTHON[@]} -eq 0 ]]; then
  echo "No working Python 3 found (tried python3, python, py -3)." >&2
  exit 1
fi

# The CI gates of what this deploys, on the committed (placeholder) tree.
"${PYTHON[@]}" "${ROOT}/scripts/check_console.py"
if [[ -n "${LEGAL_REV}" ]]; then
  echo "Legal pages from ${LEGAL_PAGES_FROM} (${LEGAL_REV:0:12}); render_legal_pages.py --check skipped."
else
  "${PYTHON[@]}" "${ROOT}/scripts/render_legal_pages.py" --check
fi

PROJECT_FLAG=()
if [[ -n "${FIREBASE_PROJECT:-}" ]]; then
  PROJECT_FLAG=(--project "${FIREBASE_PROJECT}")
fi

# Backups outside public/, so none of them is published with the site.
BACKUP="$(mktemp -d)"
SWAPPED=("${CFG}" "${JSON}")
if [[ -n "${LEGAL_REV}" ]]; then
  for page in "${LEGAL_PAGES[@]}"; do
    SWAPPED+=("${ROOT}/${page}")
  done
fi

restore() {
  # Always put the committed files back so a live hostname (or the pinned
  # legal text) is never committed.
  local k
  for k in "${!SWAPPED[@]}"; do
    if [[ -f "${BACKUP}/${k}" ]]; then
      mv -f "${BACKUP}/${k}" "${SWAPPED[$k]}"
    fi
  done
  rm -rf "${BACKUP}"
}
trap restore EXIT

for k in "${!SWAPPED[@]}"; do
  cp "${SWAPPED[$k]}" "${BACKUP}/${k}"
done

# Portable in-place substitute (GNU and BSD sed differ on -i).
subst() {
  local file="$1" from="$2" to="$3"
  "${PYTHON[@]}" - "$file" "$from" "$to" <<'PY'
import pathlib, sys
path, old, new = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = path.read_text(encoding="utf-8")
if old not in text:
    raise SystemExit(f"{path}: placeholder {old!r} not found")
path.write_text(text.replace(old, new), encoding="utf-8")
PY
}

subst "${CFG}" "__API_BASE_URL__" "${API_BASE_URL}"
subst "${JSON}" "__API_ORIGIN__" "${API_BASE_URL}"
if [[ -n "${LEGAL_REV}" ]]; then
  for page in "${LEGAL_PAGES[@]}"; do
    git -C "${ROOT}" show "${LEGAL_REV}:${page}" > "${ROOT}/${page}"
  done
fi

if [[ "${DRY_RUN:-}" == "1" ]]; then
  echo "DRY_RUN=1: checks passed and placeholders filled with ${API_BASE_URL}; stopping before firebase deploy."
  exit 0
fi

(
  cd "${HOSTING}"
  firebase deploy --only hosting "${PROJECT_FLAG[@]}"
)

# Read back what the live site serves. A deploy can "succeed" and still leave
# the consoles unable to reach the API, which a bare `firebase deploy` did on
# 2026-10-07; this fails the run instead of waiting for the next sign-in.
"${PYTHON[@]}" "${ROOT}/scripts/check_live_console.py" \
  --origin "${LIVE_ORIGIN:-https://app.sempermechanics.com}" --api "${API_BASE_URL}"

echo "Hosting deploy finished; placeholders restored under firebase-hosting/."
