# Current Task

## Task
Verify actual displayed-frame identity and physical ARCore takeover/restoration; fix device-discovered issues.

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
VERIFIED on device. arCameraTakeover PASS twice for FACE, BACK_ONLY and DUAL entry modes;
arFramePool and arDisplayedIdentity re-run PASS; 165 JVM tests, Debug/AndroidTest and lint PASS.
Required pinning ARCore back to 1.54.0 because SDK 1.56 demands an APK Play does not distribute.
Two-device spatial clicks and overlays remain out of scope and unverified.