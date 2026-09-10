# Current Task

## Milestone

M3 — Call multitasking, screen sharing and 2D collaboration.

## Task

Build M3-C: connect screen sharing to a real call, from the share entry through the peer's picture to either side stopping it, with one agreed collaboration owner.

## Scope

Pre-allocated `video_screen` track, projection capture pipeline, mediaProjection foreground service type, system consent result, share state on the control channel, collaboration ownership negotiation, lock-screen cleanup, remote screen source and layout, share entry and a permanent stop control, tests and docs.

## Delivered

- The screen m-section is negotiated at connect and left disabled, exactly like the rear camera, so starting a share needs no mid-call renegotiation.
- `ScreenShareSession` owns a dedicated non-main thread, the single-use consent/projection lifecycle and the texture pipeline feeding the screen VideoSource. The track is enabled only on a real first frame.
- `CallForegroundService` selects its foreground types explicitly and claims `mediaProjection` before the projection is obtained, releasing it back to `camera|microphone` on stop. Its notification gained a 停止共享 action.
- Ownership is claimed and granted before any device starts, so two users tapping share at the same moment resolve to one owner instead of two consent dialogs and two projections. The caller side decides a tie, the same rule an AR field conflict uses. A peer that cannot answer times out and nothing starts.
- Locking the device ends the share through the product's own cleanup rather than trusting every OEM to send a projection stop.
- A share is the one thing the device sends: every camera pauses and its hardware is released for the duration, and the arrangement comes back when the share ends unless the user changed it meanwhile. Show Me, AR and the camera button all wait for the share to end.
- `SharePresentation` reports on the control channel what this end is actually sending, with a per-share session id; it is never a command to the peer.
- The peer's share appears as a `PeerScreen` source that becomes the main view once, and follows into the in-app mini-call and system PiP. A device sharing its own screen never plays that share back.
- 更多 → 展示与协作 → 共享我的屏幕, plus a permanent stop control that survives the auto-hiding call controls.
- Fixed a recurring call-surface bug: the full-screen main view buried every thumbnail composed before it, leaving only frames and labels. Stacking now routes through `CallVideoLayout.stack`, with a regression guard.
- M3-F framework, M3-A foreground service and M3-B mini-call/PiP remain delivered.

## Validation

:app:testDebugUnitTest PASS (213 tests), :app:assembleDebug PASS, and :app:lintDebug PASS. No physical validation of consent, projection, foreground-type switching, system stop, screen-off cleanup, camera release/restore or the peer's picture — JVM tests cover none of it.

## State

M3 ACTIVE — M3-A/B and M3-C are code complete; all three await device validation. The 改由我共享 invitation and cross-device AR/share exclusion are not implemented.

## Next

Validate M3-A/B/C on two devices, including camera restore after a share on a device that really supports Show Me, and a simultaneous share tap on both ends. Then finish M3-C: the handover invitation (20s expiry, 30s cooldown), folding the AR field into the same ownership slot, and measured bitrate/codec tiers for screen content. Then M3.1 annotations. Media ownership lives in a process-scoped CallViewModel during this transition and must later move to CallSessionCoordinator.

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
