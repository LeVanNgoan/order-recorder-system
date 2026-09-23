# GitHub Publishing Checklist

Recommended repository name:

```text
order-recorder-system
```

Recommended description:

> Local-first multi-agent order capture, telemetry and reconciliation system built with Python, Android Java, Chrome Extension MV3 and SQLite.

Recommended topics:

```text
android
accessibility-service
chrome-extension
manifest-v3
python
sqlite
local-first
offline-first
telemetry
state-machine
mvc2
reliability-engineering
automation
portfolio
```

## Before first push

Run:

```bash
python scripts/public_repo_check.py
python scripts/qa_all.py
```

Then review staged files manually:

```bash
git status
git diff --cached
```

Confirm there are no `.db`, `.log`, `.xlsx`, APK/EXE artifacts, runtime configs, API keys, or signing files.

## Create the repository with GitHub CLI

```bash
git init
git branch -M main
git add .
git commit -m "chore: publish portfolio-ready monorepo"

gh auth login
gh repo create order-recorder-system --public --source=. --remote=origin --push
```

## Recommended repository settings

After publishing:

1. Add the repository description and topics above.
2. Enable **Issues** and **Actions**.
3. Set `main` as the default branch.
4. Add a branch protection/ruleset for `main`:
   - require a pull request before merge;
   - require CI status checks;
   - block force pushes;
   - block branch deletion.
5. Enable **Secret scanning** and **Push protection** if available for the account/repository.
6. Enable Dependabot alerts.
7. Pin the repository on your GitHub profile.
8. Add one sanitized dashboard image to the repository social preview if desired.

## Suggested first releases

Do not upload production/customer data.

A clean public tag can be:

```text
portfolio-mvc2-r1
```

The release notes should describe architecture and learning value rather than distributing private signing/configuration material.

## Optional CI badge after publishing

Once the final GitHub owner/repository name is known, add the action badge URL to the main README using the actual repository path. Avoid leaving `<YOUR_USERNAME>` placeholders in the published README.
