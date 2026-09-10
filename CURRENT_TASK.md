# Current Task

## Milestone

M3 — Call multitasking, screen sharing and 2D collaboration.

## Task

Build M3-A phase 1: keep established audio calls alive after Home with an ongoing-call foreground service, while video pauses until PiP exists.

## Scope

Foreground service permissions/types, quiet ongoing notification, return/mute/hang-up actions, safe process-local owner routing, established-call background policy, task-removal cleanup, tests and architecture docs.

## Delivered

- Established RTC calls no longer end solely because MainActivity stops; idle/ringing flows still stop before media exists.
- Home keeps audio/signaling alive and publishes camera-off state; returning restores video only when the user intended it enabled.
- CallForegroundService exposes a private ongoing notification with return, mute/unmute and hang-up actions and ends calls on task removal.
- ActiveCallActions routes notification actions only to the latest registered call owner; stale owners cannot clear replacements.
- M3-F screen projection framework remains delivered and unchanged.

## Validation

:app:testDebugUnitTest PASS (183 tests; 2 new action-routing tests), :app:assembleDebug PASS, and :app:lintDebug PASS. The merged Debug manifest contains the camera/microphone foreground-service permissions and service types. No physical background-call validation yet; prior M3-F checks remain valid.

## State

M3 ACTIVE — M3-A phase 1 code implemented; device validation pending. Product screen sharing is not connected yet.

## Next

Validate M3-A on two devices, then M3-B mini-call/PiP; M3-C explicit projection consent, mediaProjection service, screen frame sink/RTC sender and bilateral state; then M3.1 annotations. Media ownership still lives in CallViewModel during this transition and must later move to CallSessionCoordinator.

## Prior milestone checkpoint: M4/M5 AR (preserved)

### Task
Implement the first end-to-end AR collaboration marker slice from `docs/product/AR_INTERACTION.md`.

### Why
Camera takeover and displayed-frame identity existed, but neither side could join a field scene, place a visible spatial marker, or remove its own markers.

### Scope
Dual-role call entry, one-field conflict convergence, join/leave, displayed-frame clicks, Pin/Arrow/Circle Anchor rendering, own undo/clear, camera privacy exit, tests and docs.

### Delivered

- Field and guide roles are visible in the call options; a guide joins explicitly without starting ARCore or its camera.
- The caller coordinates simultaneous field activation so only one field remains active.
- Local and remote taps use the exact frame latched by the relevant TextureView and preserve its source reference.
- Pin, Arrow and Circle Anchors project through the current camera pose and render into the source video GPU framebuffer.
- Both participants can add concurrently, undo their latest submitted marker and clear their own submitted markers.
- Turning off the field camera ends AR first; lens switching stays disabled during AR.

### Remaining Product Work

Author/number metadata, marker list and hit targets, remote tracking readiness, transactional clear generations, reconnect snapshots, invite/only-watch/swap flows, Pointer, first-use teaching and sustained two-device validation. The interaction document remains Draft until these are resolved.

### Validation
JVM projection/session tests, Debug and AndroidTest builds, lint, synthetic device GPU rendering, physical ARCore takeover where the local RTC loop allows it, and later two-device calls.

### State
IMPLEMENTED, NOT YET PRODUCT-COMPLETE. Commits `99cf7a9` and `0bdaaa6`.

169 JVM tests, Debug/AndroidTest builds and lint PASS. Pixel 9a synthetic OES camera + marker overlay + orientation PASS. Physical ARCore availability and preparation are READY, but `arCameraTakeover` was blocked three times because its local loopback ICE reached FAILED after video frames; physical marker placement and two-device clicks remain unverified. ARCore remains pinned to 1.54.0 because SDK 1.56 requires an APK Play does not distribute on this device.
