# Apply website manual sync to `semperdic/website`

Cloud agents can write **Issues** on `semperdic/website` but cannot push
branches (403). Apply these patches on a machine with write access.

## Commits (local branch `cursor/sync-manual-with-app-819b`)

1. Sync Manual / Privacy / Troubleshooting / CONTENT-SYNC with `semperdic-app` tip
2–3. Rename IndicVisionDIC → `semperdic/semperdic-app` in `RELEASING.md`

`npm run build` passed on the agent host. Download/`manifest.json` left unpublished.

## Apply

```bash
git clone https://github.com/semperdic/website.git && cd website
git checkout -b cursor/sync-manual-with-app-819b
git am path/to/docs/ops/website-sync/000*.patch
git push -u origin HEAD
gh pr create --base main \
  --title "docs: sync manual and privacy with semperdic-app tip" \
  --body "See docs/ops/website-sync/APPLY.md in semperdic-app for context."
```

Or from the agent checkout (if you grant push):

```bash
cd /home/ubuntu/src/website   # already has the 3 commits
git push -u origin cursor/sync-manual-with-app-819b
```

## Follow-up

Re-capture `result-lattice.webp` / `home.webp` for Save graph · View / scrub /
Only-in-cloud chrome (prose already documents them).
