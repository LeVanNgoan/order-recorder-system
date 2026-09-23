# Engineering Case Study — Order Recorder System

## Problem

The operational requirement looks simple: record order identifiers, timestamps, and customer contact information from multiple merchant platforms. In practice, the failure modes are distributed across browser DOM changes, Android UI state, notification timing, device performance, local networking, and staff recovery workflows.

A system that reports only "captured" or "not captured" hides the most important questions:

- Was the order detected at all?
- Did automation start on the correct order?
- Was the phone number actually captured?
- Was it the correct semantic phone role?
- Did the agent capture locally but fail to synchronize?
- Did staff manually recover the data after an automation failure?

The project evolved around making those states observable and recoverable.

## Architecture decision: local-first agents

Both agents are intentionally capable of persisting order state before the Hub is available. The Hub is a management/reconciliation center, not a hard dependency of the capture hot path.

This reduces a common distributed-systems failure mode: a temporary LAN/Hub outage should not turn into order-data loss.

## Architecture decision: business rows and telemetry are separate

An order row answers "what data do we currently have?". An event stream answers "what happened to this order?".

The Hub therefore treats lifecycle telemetry separately:

```text
SEEN
  ├─ CAPTURED
  ├─ RISK -> CAPTURED
  └─ RISK -> MISS -> manual recovery
```

This enables reconciliation to use `SEEN` as its denominator instead of counting only successful database rows.

## Reliability incident: Android UI automation

The Android agent operates on a third-party merchant UI using Notification Listener and Accessibility APIs. This creates timing and state uncertainty:

- a notification intent can open late;
- an old order can still be on screen;
- the contact sheet can render asynchronously;
- UI overlays can hide another accessibility window;
- a low-powered device can enter a degraded/laggy state.

The response was not to add infinite retries. Instead, the design uses:

- bounded attempts;
- a processing lease;
- wrong-order guards;
- settle windows;
- queue fairness;
- terminal review state;
- black-box technical logs;
- early `RISK` telemetry.

The engineering lesson is that **bounded failure is safer than aggressive automation** when operating another application's UI.

## Reliability incident: network coupling

Hub discovery and telemetry can generate background work, but networking must not block phone capture. The agent therefore persists local state first and performs Hub synchronization on a background executor.

This is a practical example of isolating an unreliable external dependency from the critical path.

## Data correctness: a harder problem than capture rate

A high capture rate is meaningless if the captured number is semantically wrong. One discovered failure mode was the distinction between the purchaser/contact and the intended receiver contact.

This changed the project's quality model from a single "capture rate" into three separate questions:

1. **Detection completeness** — was every real order observed?
2. **Capture completeness** — did detected orders obtain a phone number?
3. **Capture correctness** — is the number bound to the intended receiver role?

The planned Receiver-Only Phone Binding work is intentionally separated from the MVC2 structural refactor so any regression can be attributed to one change set.

## Operational recovery

The Hub supports manual backup, editing, soft delete, restore, audit trails, early Windows notifications, and Excel import. These are not secondary admin features: they are part of the reliability model.

A production automation system needs a human recovery path when confidence is low.

## Privacy decisions

The system uses a short operational retention period, and backup retention is intentionally bounded by the same policy. Technical logging is designed not to intentionally include full customer phone numbers.

The public GitHub edition further removes all real runtime data, logs, databases, API keys, and signing material.

## Maintainability migration

The original system grew through real operational fixes. Rewriting it wholesale would introduce unnecessary regression risk, so the monorepo uses a strangler-style MVC2 migration:

```text
old proven runtime
      ↓
MVC2 adapter boundaries
      ↓
new services/repositories per use case
      ↓
legacy runtime gradually shrinks
```

This demonstrates an important maintenance principle: architecture improvements do not need to require a big-bang rewrite.

## Skills demonstrated

- distributed/local-first system design;
- Android Accessibility and notification automation;
- Chrome Extension Manifest V3;
- Python HTTP services and SQLite;
- event telemetry and idempotency;
- state-machine/retry design;
- audit and recovery workflows;
- data retention/privacy controls;
- regression testing and CI;
- incident-driven debugging;
- incremental architecture migration.
