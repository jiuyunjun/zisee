# HANDOFF

## Status
READY_FOR_REVIEW

## Objective
Fix Show Me orientation; reduce Wi-Fi to 5G freezes (TURN fallback allowed); continue AR implementation.

## Active Task
Completed camera correction, handover scheduling/native ICE tuning, and AR control-channel integration. Next milestone is AR camera/media/rendering and exact displayed-frame identity integration.

## Last Good Checkpoint
commit: e4499d0
build: PASS
tests: PASS

## Current Work
Implementation and validation complete for these checkpoints. Updated debug APK is installed on the attached Android device. No production service deployment or Git push performed.

## Repository State
Expected pre-existing unstaged files: AGENTS.md; NativeRtcSession.kt (only the old post-bind targetRotation workaround); ActiveCall.kt; CallVideoLayout.kt; CallVideoLayoutTest.kt. Preserve these edits; they are excluded from our commits. ActiveCall.kt also changed externally during this run. Builds used the combined working tree, including those pre-existing UI changes. No unexplained task WIP remains.

## Completed
- 646e07a: normalize CameraX SurfaceTexture camera rotation/mirror before WebRTC frame metadata; retain texture crop and balanced references.
- b430cfd: record route time before signaling IO; wake exchange for new candidates; keep restart adoption in fast polling; reduce native receiving/backup path detection waits while retaining ALL/P2P/TURN candidates.
- e4499d0: dedicated reliable ordered AR DataChannel id 2, explicit ready/join/joined/leave/ended lifecycle, session/result correlation, bounded ingress/backpressure, GL controller adapter, per-call cleanup and failure isolation.

## Verified
- 139 JVM tests: zero failures/errors/skips.
- :app:assembleDebug, :app:assembleDebugAndroidTest, :app:lintDebug PASS.
- Attached Android cameraTransform instrumentation PASS (real Matrix, no physical camera orientation assertion).
- Attached Android arChannel instrumentation PASS: two actual PeerConnections/SCTP/DTLS; synthetic field endpoint; create/result/clear/leave/ended; malformed-message AR shutdown releases endpoint and camera-state still sends.
- Attached Android default RTC smoke PASS: actual camera, ICE, encode/decode, sender ceilings, ICE restart/rollback and resource release. Local loopback only, no TURN.
- Task diffs reviewed; staged text verified UTF-8 without BOM. No credentials or private media added.
- Latest debug and AndroidTest APK installed successfully.

## Not Yet Verified
- Two-device Show Me front/back upright image and mirror text across all display orientations.
- Wi-Fi/5G handover interruption duration, weak Wi-Fi, real TURN UDP/TLS fallback, relay-to-P2P migration and probe power cost.
- Real ARCore capture/anchors/depth/texture mapping, AR video encoding/overlay rendering, remote displayed-frame timestamp mapping and AR UI.

## Known Issues / Blockers
- Existing default JAVA_HOME points to Java 8; use Android Studio jbr for builds.
- Handover changes remove specific waits but do not establish measured seamless recovery. Existing 30-second signaling retry budget remains.
- AR control integration is callable through NativeRtcSession.arCollaboration, but no AR UI or camera startup is enabled yet. Never supply a latest-time hint as the displayed AR frame identity.
- AR malformed/over-budget traffic closes AR for the rest of this call, preserving RTC. Outstanding marker requests are bounded to 16; leave/rejoin clears them without automatic command replay.
- Initial AR test compile used nonexistent FRAME_NOT_FOUND; corrected to FRAME_MISSING and reran all checks successfully.

## Decisions
- Preserve independent camera tracks and renderer mirror policy; no CPU pixel copies.
- Keep native ICE ALL rather than forcing relay-only and discarding working direct routes. Short-lived TURN remains configured from call setup.
- New AR transport owns an explicitly attached field endpoint; failure/hangup closes it on its GL dispatcher before peer/EGL teardown. That dispatcher must stay alive until close completes. Camera-lease close must not block waiting for the RTC dispatcher.
- AR ready is an offer, not remote permission to start a camera; guide must explicitly join and receive acknowledgement.

## Next Action
1. Measure two-phone handover with the installed APK on both ends and compare actual last/first media frame and audio gaps. Inspect RTC_NETWORK_CHANGED, RTC_ROUTE_RECOVERED and RTC_SELECTED_CANDIDATE; no private addresses/SDP in reports.
2. Read docs/architecture/AR_FRAMEWORK.md, ArCoreBackend.kt and ArControllerEndpoint before implementing AR camera ownership and GPU frame delivery. Establish exact AR texture-to-displayed-video reference mapping before exposing spatial clicks.
3. Preserve unstaged user work; create a new CURRENT_TASK.md for the next atomic milestone.

## Done When
These checkpoints are reviewable with passing available checks; physical handover and complete AR product acceptance remain explicitly outstanding.

## Relevant Commands
From android with JAVA_HOME=C:/Program Files/Android/Android Studio/jbr:
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb shell am instrument -w -e cameraTransform true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb shell am instrument -w -e arChannel true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb shell am instrument -w com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation

## Latest Commits
e4499d0 feat: connect AR collaboration control channel
b430cfd fix: reduce ICE handover detection and signaling waits
646e07a fix: normalize concurrent camera texture orientation
