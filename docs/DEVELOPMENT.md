# Development Guide

## Prerequisites

### Common

- Git
- Python 3.12+
- Node.js 22+

### Android agent

- Java 17
- Android SDK 35
- Gradle 8.x

### Windows Hub packaging

- Windows runner/PC
- PyInstaller

## One-command repository QA

```bash
python -m pip install -r apps/hub/requirements.txt
python scripts/qa_all.py
```

`qa_all.py` runs Hub regression tests, the Android stable-source verifier, and JavaScript parser/resource checks for the Chrome extension.

## Hub development

```bash
cd apps/hub
python -m pip install -r requirements.txt
python run.py
```

Runtime state is created outside the repository (`LOCALAPPDATA` on Windows; a home-directory application folder on other OSes).

## Chrome extension development

1. Open `chrome://extensions`.
2. Enable Developer Mode.
3. Choose **Load unpacked**.
4. Select `apps/grabfood-extension`.
5. After changes, click **Reload** on the extension card.

Run JS parser checks:

```bash
node --check apps/grabfood-extension/service-worker.js
node --check apps/grabfood-extension/src/controller/grab-monitor.js
```

## Android development

Debug build:

```bash
cd apps/shopeefood-agent
gradle :app:assembleDebug
```

Run source-invariant verification:

```bash
python verify_source_v2.0.10.py
```

The public repository does not include a signing keystore. See `apps/shopeefood-agent/keystore/README.md` for secure signing guidance.

## Shared contracts

Wire-format changes should be designed in `shared/contracts` before implementation. Backward compatibility must be considered whenever an existing agent and a new Hub may coexist during rollout.

## Regression discipline

For Android capture logic in particular:

- do not change queue, retry, timing, parsing, and UI navigation in the same commit;
- reproduce the failure using technical evidence first;
- add diagnostic visibility before adding more automation;
- prefer a bounded `RISK/MISS` path over an unbounded retry loop.
