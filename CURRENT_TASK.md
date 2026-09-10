# Current Task

## Milestone

M3 — Call multitasking, screen sharing and 2D collaboration.

## Task

Build M3-B: add an in-app mini-call and Android Picture-in-Picture while keeping one active call independent of Activity recreation.

## Scope

Process-scoped transitional call ownership, one-source compact video selection, draggable in-app mini-call, Android PiP entry/actions/aspect ratio, visible-video background policy, AR guardrails, tests and docs.

## Delivered

- Active calls are held by the process-level AppContainer transitional owner, so Activity recreation or PiP closure does not dispose RTC resources.
- Back from the full call opens a draggable in-app mini-call with restore, mute and hang-up controls while the rest of Zisee remains usable.
- Home enters system PiP on supported devices for normal and Show Me calls; PiP renders one selected remote source and exposes mute/hang-up RemoteActions.
- Video stays active while PiP is visible. Closing PiP or backgrounding without it pauses camera video but keeps audio/signaling and the ongoing notification.
- Local AR field mode must end before in-app minimization; system Home enters remote-view PiP while the existing pause cleanup ends the local field. A remote AR guide can watch the field in PiP.
- M3-F screen projection framework and M3-A foreground service remain delivered.

## Validation

:app:testDebugUnitTest PASS (186 tests), :app:assembleDebug PASS, and :app:lintDebug PASS. No physical PiP/background-call validation yet; prior M3-F checks remain valid.

## State

M3 ACTIVE — M3-A/B code implemented; device validation pending. Product screen sharing is not connected yet.

## Next

Validate M3-A/B on two devices, then implement M3-C explicit projection consent, mediaProjection service, screen frame sink/RTC sender and bilateral state; then M3.1 annotations. Media ownership lives in a process-scoped CallViewModel during this transition and must later move to CallSessionCoordinator.

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
