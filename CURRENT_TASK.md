# Current Task

## Task
Expose user-triggered AR camera controls and bind them to call and Activity lifecycle.

## Why
The media APIs exist but no in-call path invokes preparation, capture or foreground cleanup.

## Scope
Call AR coordinator, call view model, AR menu composables, minimal ActiveCall slot, native AR state/cleanup, targeted tests and docs. Preserve existing five modified files.

## Planned Changes
Capability check; explicit camera/privacy explanation; permission/install prepare; guarded start/stop; rotation geometry; onPause stop; state/error feedback. Preserve existing background-hangup policy. Do not expose remote spatial clicks.

## Acceptance Criteria
No automatic camera activation after lifecycle loss or stale permission/install callback. Exit stays available during startup. Ordinary camera modes restore on exit. Unsupported devices retain video calling. AR status and failure are visible. Build/tests pass; real device checks recorded honestly.

## Validation
JVM intent/lifecycle tests, Debug/AndroidTest build and lint, available device display/camera verification.

## State
IMPLEMENTING