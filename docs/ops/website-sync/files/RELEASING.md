# Releasing Semper (public distribution)

The private app repository builds the signed APK. **This repo does not auto-publish builds.** Each customer-facing release is manual.

## Checklist

1. **Build** a signed release APK from the private Android app repo Release workflow (or local release assemble).
2. **Host the APK** (pick one):
   - **Preferred:** create a GitHub Release on this repo and attach the APK; copy the asset URL.
   - Or place a small APK under `public/downloads/` (avoid committing large binaries long-term).
3. **Update** [`public/downloads/manifest.json`](public/downloads/manifest.json):

   ```json
   {
     "version": "1.0",
     "versionCode": 42,
     "apkUrl": "https://github.com/semperdic/website/releases/download/v1.0/semper-1.0.apk",
     "apkSha256": "optional-hex-digest",
     "notes": "Short changelog for testers.",
     "playStoreUrl": "https://play.google.com/store/apps/details?id=com.indicvision.semper",
     "publishedAt": "2026-08-03"
   }
   ```

4. **Deploy the site** — push to `main` (GitHub Actions → Pages), or:

   ```bash
   npm run build
   ```

   and let the workflow publish `dist/`.

5. **Publish** the same build on Google Play (internal / closed / production as appropriate).
6. **Announce** (optional): pin a Discussion under **Announcements**.

## Verify

- Open https://semperdic.github.io/website/download/ — version and links match the manifest.
- Direct APK URL downloads the package.
- Play Store listing opens for `com.indicvision.semper` when live.

## Private vs public

| Step | Where |
|------|--------|
| Sign APK | Private app repo `release.yml` |
| Host APK + site | This repo → GitHub Releases + GitHub Pages |
| Store listing | Google Play Console |
| Customer Q&A / bugs | This repo Discussions / Issues |
