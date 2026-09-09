# HANDOFF

## Status
IN_PROGRESS

## Objective
Finish camera fix; reduce Wi-Fi to cellular handover stalls (temporary TURN allowed); continue AR integration.

## Active Task
Normalize concurrent camera textures before WebRTC frame rotation.

## Last Good Checkpoint
commit: d201cb3
build: NOT_RUN
tests: NOT_RUN

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
Commit camera fix, then investigate handover policy and implement next AR integration milestone.

## Done When
Fix committed with passing available checks and explicit device validation limits.

## Relevant Commands
From android with JAVA_HOME=C:/Program Files/Android/Android Studio/jbr: ./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

## Latest Commits
d201cb3 feat: add ARCore spatial collaboration framework
