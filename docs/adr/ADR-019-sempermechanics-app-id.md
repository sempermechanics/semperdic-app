# ADR-019: The app is `com.sempermechanics.semper`; "indic" leaves the code

**Status:** Accepted, built (not yet released)
**Date:** 2026-10-03
**Deciders:** app owner

## Context

The app shipped as `com.indicvision.semper`, the Kotlin package had the same
name, and the code carried the company name into its own identifiers: the
backend client `IndicApi*`, the `INDIC_*` build keys, the `indic_*` prefs
files, the `IndicDeviceKeyEc` Keystore alias, the backend's `indic.*` logger
names and two string keys. The product is Semper by Semper Mechanics
(`sempermechanics.com`, GitHub org `sempermechanics`), and "indic" names
neither the app nor anything in it.

An Android `applicationId` cannot change in place. A new id is a new app: a new
Play listing, a new Firebase Android app, new App Links entries, and empty
on-device storage on first install.

## Decision

- **App id, namespace and Kotlin package** are `com.sempermechanics.semper`
  (`app/build.gradle.kts`). The benchmark module and every source set moved
  with it. Workers, `SemperNativeLib` and Activities keep their simple names
  (ADR-015); only the package changes, which a new app id allows because no
  install of it has queued work.
- **Engine.** The JNI exports are `Java_com_sempermechanics_semper_*`. The
  engine also keeps forwarders under the old `Java_com_indicvision_semper_*`
  names, so the app and the material_testing fork can bump the engine before or
  after their own move. The engine submodule's folder is `engine/` (it was
  `native/`).
- **Names.** `IndicApi*` is `SemperApi*`, the build keys are `SEMPER_*`, the
  prefs files are `semper_*`, the Keystore alias is `SemperDeviceKeyEc`, and the
  backend loggers are `semper.*`. The new app starts with empty storage, so the
  prefs and Keystore renames need no migration.
- **Old spellings still read.** `app/build.gradle.kts` reads `SEMPER_*` and
  falls back to the `INDIC_*` key in `local.properties` and the environment;
  `release.yml` takes `vars.SEMPER_API_BASE_URL || vars.INDIC_API_BASE_URL`.
- **Backend.** `backend/app/apps.py` maps `com.sempermechanics.semper` and
  `com.sempermechanics.materialtesting` to the same short names (`semper`,
  `materialtesting`) as the old ids, which stay mapped. A phone keeps its
  device slot (ADR-010) and its backups (ADR-014, tagged by short name) across
  the move.
- **App Links.** `assetlinks.json` lists the new and old packages.
- **What keeps "indic", and why:**
  - the wire and file formats `indic.session.metadata/N` and
    `indic.account.export/1`: old backups and exports must still parse;
  - GCP and Firebase project ids (`indicvision-dic-app`,
    `indicvision-dic-app-auth`) and service-account names: these can't be
    renamed;
  - company email addresses and the legal text, which name the operator;
  - history: the CHANGELOG and earlier ADRs keep the ids they were decided under.

## Consequences

- Release needs the owner's steps first: register `com.sempermechanics.semper`
  in Firebase (SHA keys, App Check, OAuth client) and replace
  `app/google-services.json`; create the Play listing; add the new app-signing
  fingerprint to `assetlinks.json` if Play signs with a new key; set the
  `SEMPER_API_BASE_URL` var; deploy the backend and Hosting.
- Existing users install the new app beside the old one; their backups restore
  into it. On-device analyses that were never backed up stay in the old app.
- material_testing must switch to `com.sempermechanics.materialtesting` **in
  the same merge** that brings this code in. Otherwise its installs, still on
  the old id, would read the renamed prefs files and Keystore alias as empty and
  lose sign-in, settings and the device key.
- The fallbacks (engine forwarders, old ids in `apps.py` and `assetlinks.json`,
  `INDIC_*` keys) are removed once no build sends the old ids: TD-176.
