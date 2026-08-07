# Apply: copy app manual sources into `semperdic/website`

This cloud agent can only push to `semperdic/semperdic-app`. The website changes
are packaged here for you to apply on a machine with write access to
`semperdic/website`.

## What this fixes

Previous Manual sync **referenced** private-app paths (`semperdic-app/docs/…`)
instead of shipping those files in the public website repo. This package:

- Adds `docs/source/` (OPERATING_MANUAL, WORKFLOWS, legal, images)
- Refreshes `public/manual/screenshots/*.webp` and Manual SVGs from that snapshot
- Retargets `docs/CONTENT-SYNC.md` and screenshots README to **local** copies
- Softens maintainer docs so they do not link a private GitHub URL

## PowerShell (recommended)

```powershell
cd path\to\website
git checkout main
git pull
git checkout -b cursor/copy-app-manual-sources-819b

# Clone this download branch (or sparse-checkout the sync folder), then:
$src = "path\to\semperdic-app\docs\ops\website-sync\files"
Copy-Item -Path "$src\*" -Destination . -Recurse -Force

git add -A
git commit -m "docs: copy app manual sources into website repo"
git push -u origin HEAD
# Open PR into main
```

### Or unpack the tarball

```powershell
cd path\to\website
git checkout -b cursor/copy-app-manual-sources-819b origin/main
tar -xzf path\to\semperdic-app\docs\ops\website-sync\website-copy-app-sources.tar.gz
# If tar is unavailable, use Copy-Item on the files\ tree above.
```

## Verify

```bash
npm ci
npm run build
```

Then open a PR: branch `cursor/copy-app-manual-sources-819b` → `main`.
