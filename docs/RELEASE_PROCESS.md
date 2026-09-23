# Release / Build Process

This public portfolio repository separates **reviewable source** from **private production signing/configuration**.

## Required checks

Before tagging or publishing artifacts:

```bash
python scripts/public_repo_check.py
python scripts/qa_all.py
```

Do not publish a release when regression tests or source-invariant checks fail.

## Hub

Use `.github/workflows/build-hub.yml` or build on Windows with PyInstaller.

The packaged executable must not embed runtime databases, API keys, or customer exports.

## ShopeeFood Android agent

Public CI builds a **debug artifact** only. The repository intentionally excludes release signing material.

For a private signed release:

1. provide signing material through secure local configuration or GitHub Secrets/Environments;
2. never write the keystore/password into source-controlled files;
3. run the stable-source verifier before build;
4. preserve the expected Android signing lineage for any in-place production update.

## GrabFood extension

Use `.github/workflows/package-grab.yml` or package the extension directory locally.

Do not include Chrome profile data, extension local-storage exports, or real order spreadsheets.

## Versioning

Component versions remain independent. The monorepo architecture revision is tracked separately in `VERSION.json`.

For portfolio publication, a tag such as `portfolio-mvc2-r1` is clearer than implying that the public repository contains deployable production credentials.
