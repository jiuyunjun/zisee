# HANDOFF

## Status
READY_FOR_REVIEW

## Objective
Continue AR Assist implementation while preserving existing camera/UI work and handover improvements.

## Active Task
Exact-frame AR camera texture mapping and GPU background rendering primitives are implemented and verified. Full in-call AR remains incomplete.

## Last Good Checkpoint
commit: 4440862 (baseline; AR milestone commit pending)
build: PASS (current combined working tree)
tests: PASS (current combined working tree)

## Current Work
ArCoreBackend captures CPU-image-to-OES mapping with the historical frame. renderCamera only accepts the current reference. Every native update, pause and close invalidates old texture access. ArCameraRenderer draws to a caller-owned framebuffer on the owner GL thread/context. EIS disabled explicitly. No new dependencies or AR UI entry.

## Repository State
Preserve five pre-existing unstaged files: AGENTS.md; NativeRtcSession.kt (post-bind targetRotation workaround); ActiveCall.kt (user UI edits plus VIDEO_PROBE tip); CallVideoLayout.kt; CallVideoLayoutTest.kt. They are excluded from AR commits. Tests/builds include this combined working tree.
Expected AR changes pending commit: CURRENT_TASK.md, HANDOFF.md, AR_FRAMEWORK.md, ArCoreBackend.kt, new ar/render files, CameraTextureMappingTest.kt, ArCameraRenderSmoke.kt, RtcSmokeInstrumentation.kt.

## Completed
- Previous AR framework: history/depth/plane resolution, anchors and versioned protocol.
- e4499d0: reliable ordered AR DataChannel id 2; ready/join/joined/leave/ended, bounded traffic, endpoint cleanup and RTC failure isolation.
- Current milestone: affine raw CPU-image texture mapping, exact current-texture gate, GPU draw and thread/EGL checks.
- Existing camera orientation and handover commits remain in history; latest baseline 4440862. Previous handover measurements and remaining promotion interruption are recorded in commit 2d90d49.

## Verified
- 159 JVM tests: zero failures/errors/skips (including 3 new mapping/gate tests).
- :app:assembleDebug, :app:assembleDebugAndroidTest and :app:lintDebug PASS, including rerun after adding backend EGL-context validation.
- Attached device arCameraRender instrumentation PASS: synthetic four-color OES texture, real EGL/shader drawing and pixel corner orientation. No actual ARCore camera involved. Test ran before the final backend-only EGL guard; render code/test unchanged.
- Debug and test APKs installed for that device test.
- Initial sandbox Gradle run failed on network permission; approved outside-sandbox rerun passed.
- Diff review and UTF-8 validation performed before commit; no sensitive payloads/media added.

## Not Yet Verified
Actual ARCore texture FOV/alignment, anchors/depth, camera lease handover, EGL recreation and foreground/background behavior. Two-device AR video, displayed-frame identity, spatial clicks and overlays. Physical Show Me rotation and Wi-Fi/cellular/TURN handovers remain separate device acceptance items.

## Known Issues / Blockers
No build blocker. Default JAVA_HOME is Java 8; use Android Studio jbr. AR UI/camera startup remains disabled. The OES texture is mutable and must not be handed directly to an asynchronous encoder. Historical frames cannot render a camera texture after its next native update. Output dimensions should preserve CPU-image aspect ratio.

## Decisions
- Map from IMAGE_NORMALIZED, without baking in display VIEW crop/rotation; downstream display geometry remains explicit.
- Use existing WebRTC GlRectDrawer and zero CPU camera pixel copies; readPixels exists only in instrumentation.
- Reject stale/missing texture references rather than using a latest timestamp. Remote spatial clicks still require exact displayed-frame identity.
- Media owner must keep GL/EGL alive until endpoint/controller/renderer cleanup completes. Camera lease close must not wait synchronously for RTC dispatcher.
- Preserve independent tracks, P2P/TURN fallback and all unrelated edits.

## Next Action
Implement exclusive camera lease integration and bounded reference-counted RGB framebuffer delivery into WebRTC. Read AR_FRAMEWORK.md, ArCoreBackend.renderCamera, ArCameraRenderer, ArControllerEndpoint and NativeRtcSession camera lifecycle first. ARCore OES must be copied on GPU before next Session.update; retained RGB buffers cannot be recycled until downstream release. Establish source-to-encoded-to-displayed identity before enabling spatial clicks. Add actual ARCore capture/lifecycle device tests when the media owner exists.

## Done When
This atomic render milestone is committed with passing available checks. Full AR product acceptance still requires camera/media ownership, frame identity, UI and real two-device annotation verification.

## Relevant Commands
From android, JAVA_HOME=C:/Program Files/Android/Android Studio/jbr:
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb shell am instrument -w -e arCameraRender true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb shell am instrument -w -e arChannel true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation

## Latest Commits
4440862 feat: say why a handover seeded nothing
2d90d49 docs: record the handover measurements and what remains
e4499d0 feat: connect AR collaboration control channel
