# HANDOFF

## Status
IN_PROGRESS

## Objective
Finish camera fix; reduce Wi-Fi to cellular handover stalls (temporary TURN allowed); continue AR integration.

## Active Task
Reduce handover waits; then continue AR control-channel integration.

## Last Good Checkpoint
commit: b430cfd
build: PASS
tests: PASS

## Current Work
Investigated DualCameraCapture: SurfaceTexture camera transform is retained while TransformationInfo.rotationDegrees is applied again. Match WebRTC Camera2Session normalization.

## Repository State
Pre-existing modified files: AGENTS.md, NativeRtcSession.kt, ActiveCall.kt, CallVideoLayout.kt, CallVideoLayoutTest.kt. Preserve all; excluded from this fix commit. HANDOFF.md/CURRENT_TASK.md were absent on entry.

## Completed
Read existing WIP and orientation design; verified HEAD matches d201cb3.

## Verified
127 JVM tests passed; assembleDebug, assembleDebugAndroidTest, lintDebug passed. Android Matrix regression passed on attached device; updated debug and test APK installed.

## Not Yet Verified
Physical dual camera and two-peer orientation/mirroring; network handover and AR integration.

## Known Issues / Blockers
JAVA_HOME points to Java 8; use Android Studio jbr for Gradle. Prior post-bind targetRotation workaround does not undo the texture transform.

## Decisions
Normalize GPU texture coordinates without pixel copies; preserve CameraX per-frame rotation and renderer mirror policy. Respect hasCameraTransform for processed surfaces.

## Next Action
Implement handover timing/candidate wake fixes and validate; then implement AR session/control channel wiring.

## Done When
Fix committed with passing available checks and explicit device validation limits.

## Relevant Commands
From android with JAVA_HOME=C:/Program Files/Android/Android Studio/jbr: ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

## Latest Commits
d201cb3 feat: add ARCore spatial collaboration framework

## Latest Checkpoint
646e07a fixes camera texture orientation. Android matrix regression PASS. Existing unrelated UI and NativeRtcSession post-bind workaround still unstaged.

## Handover Checkpoint
Implemented callback-time recovery timing, candidate wake, pending generation fast polling, and native backup/receiving timeouts. 130 JVM tests, assembleDebug and lintDebug PASS. Real network recovery timing remains unverified. Next: AR control channel with scoped session admission, bounded queues, owner-thread dispatch and lifecycle cleanup.

## Active AR Work
Start AR control transport milestone. b430cfd is the handover checkpoint; 130 JVM tests/build/lint PASS. Preserve pre-existing UI/AGENTS/NativeRtcSession workaround edits (ActiveCall also changed externally during this run). No AR capture UI is being enabled without trustworthy frame correspondence.

## AR Validation Checkpoint
AR collaboration transport/controller dispatch implemented; 139 JVM tests PASS, debug/test builds and lint PASS. Attached Android: paired AR channel smoke (including malformed-message shutdown) PASS; existing camera/ICE/codec/restart/release smoke PASS. Updated APK installed. Initial AR test compile failed for incorrect enum FRAME_NOT_FOUND; corrected to FRAME_MISSING and all checks rerun. Next: review/stage task files only and commit. UI, AR camera handoff, rendering and exact remote frame references remain future work.
