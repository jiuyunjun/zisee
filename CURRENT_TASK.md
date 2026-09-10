# Current Task

## Task
Implement the first end-to-end AR collaboration marker slice from `docs/product/AR_INTERACTION.md`.

## Why
Camera takeover and displayed-frame identity existed, but neither side could join a field scene, place a visible spatial marker, or remove its own markers.

## Scope
Dual-role call entry, one-field conflict convergence, join/leave, displayed-frame clicks, Pin/Arrow/Circle Anchor rendering, own undo/clear, camera privacy exit, tests and docs.

## Delivered

- Field and guide roles are visible in the call options; a guide joins explicitly without starting ARCore or its camera.
- The caller coordinates simultaneous field activation so only one field remains active.
- Local and remote taps use the exact frame latched by the relevant TextureView and preserve its source reference.
- Pin, Arrow and Circle Anchors project through the current camera pose and render into the source video GPU framebuffer.
- Both participants can add concurrently, undo their latest submitted marker and clear their own submitted markers.
- Turning off the field camera ends AR first; lens switching stays disabled during AR.

## Remaining Product Work

Author/number metadata, marker list and hit targets, field-owner clear-all, revoke-guide permission, remote tracking readiness, transactional clear generations, reconnect snapshots, invite/only-watch/swap flows, Pointer, first-use teaching and sustained two-device validation. The interaction document remains Draft until these are resolved.

## Validation
JVM projection/session tests, Debug and AndroidTest builds, lint, synthetic device GPU rendering, physical ARCore takeover where the local RTC loop allows it, and later two-device calls.

## State
IMPLEMENTED, NOT YET PRODUCT-COMPLETE. Commit `99cf7a9`.

168 JVM tests, Debug/AndroidTest builds and lint PASS. Pixel 9a synthetic OES camera + marker overlay + orientation PASS. Physical ARCore availability and preparation are READY, but `arCameraTakeover` was blocked three times because its local loopback ICE reached FAILED after video frames; physical marker placement and two-device clicks remain unverified. ARCore remains pinned to 1.54.0 because SDK 1.56 requires an APK Play does not distribute on this device.
