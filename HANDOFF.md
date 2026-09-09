# HANDOFF

## Status
READY_FOR_REVIEW

## Objective
Implement the next AR media milestone: camera takeover, WebRTC delivery and remote displayed-frame identity.

## Active Task
Implementation complete for media APIs and H264 source identity; device acceptance remains incomplete. No AR UI entry or spatial-click tool enabled.

## Last Good Checkpoint
commit: fe4ecc4 (camera media checkpoint; identity commit follows)
build: PASS (current combined working tree)
tests: PASS (162 JVM tests)

## Current Work
ArVideoCapture owns ARCore on its GL worker. Three retained RGB slots feed video_back. NativeRtcSession startAr/stopAr/updateArGeometry manage camera lease and restore previous capture, with startup/restoration barriers against hangup. H264 SEI carries frame identity; decoded texture tags reach VideoFeed. TextureViewRenderer publishes identity only after exact SurfaceTexture timestamp lookup.

## Repository State
Preserve the five pre-existing modified files: AGENTS.md; NativeRtcSession.kt concurrent post-bind targetRotation hunk; ActiveCall.kt user UI/probe tip; CallVideoLayout.kt thumbnail sizing; CallVideoLayoutTest.kt corresponding sizing test. They remain excluded from AR commits. All other current changes belong to this task and must be retained until committed. No debug diagnostic logging remains.

## Completed
- fe4ecc4: exclusive camera lease, retained GPU video delivery, AR rear-track presentation and previous-mode restoration.
- Identity implementation: versioned H264 SEI, bounded exact codec correlation, tagged texture ownership and actual surface-latch lookup.
- Rear track prefers H264 with other codecs retained as ordinary video fallbacks.
- Tests for SEI escaping/validation, duplicate and bounded correlation; native GPU pool and H264 loopback fixtures.

## Verified
- 162 JVM tests PASS; :app:assembleDebug, :app:assembleDebugAndroidTest, :app:lintDebug PASS.
- Connected-device arFramePool PASS: bounded exhaustion, retained pixel stability and deferred GL cleanup.
- Connected-device arVideoIdentity PASS: synthetic RGB through actual PeerConnection/H264 RTP and MediaCodec with exact source timestamp/session. Repeated after codec correction.
- Initial JNI decode crash (null DecodeInfo) fixed; initial decoder output timeout (changed timestamp rejected by native bookkeeping) fixed. No failures concealed.
- Latest whitespace-only close-block indentation: compileDebugKotlin PASS.

## Not Yet Verified
Physical ARCore camera takeover/restore, raw image orientation/depth alignment, lifecycle/hangup races on hardware, EGL recreation, actual TextureView identity, two-device spatial clicks and overlays. glFinish performance/power not measured.

## Known Issues / Blockers
MIUI denied background launch of ArVideoTestActivity (result 102); arDisplayedIdentity could not reach renderer assertions. Instrumentation process stopped after stalled launch. No permission bypass/settings changes. The test now bounds Activity launch to 5 seconds; rerun with application foreground. AR UI startup remains intentionally absent from this media milestone. Non-H264 or I420/cropped paths fail closed for identity while video continues.

## Decisions
Use in-band per-access-unit SEI, not a latest timestamp or content hash. Native decoder timestamp is immutable for WebRTC bookkeeping; attach identity to texture instead. Renderer-local tokens belong only to the renderer. Retained RGB frames keep their independent GL worker alive after camera close until downstream releases. Lease return posts restoration without synchronously waiting for RTC. Preserve raw CPU-image aspect ratio. Keep existing P2P/TURN behavior.

## Next Action
Review identity checkpoint, then run arDisplayedIdentity with the app foreground. Add foreground AR UI/controller integration calling prepare, startAr, updateArGeometry and stopAr on pause; verify real ARCore and two-device flow before exposing spatial controls. Use docs/architecture/AR_FRAMEWORK.md 1.3/1.4 contracts.

## Done When
Media implementation committed with available tests passing and explicit remaining physical-device acceptance. Full AR product completion requires UI, lifecycle device checks and spatial annotations on two devices.

## Relevant Commands
JAVA_HOME=C:/Program Files/Android/Android Studio/jbr
./android/gradlew.bat -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb shell am instrument -w -e arVideoIdentity true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation
adb shell am instrument -w -e arDisplayedIdentity true com.lazydoglab.zisee.dev.test/com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation

## Latest Commits
fe4ecc4 feat: deliver AR camera frames through retained WebRTC textures
c34f0f6 docs: record AR camera rendering checkpoint
431a7a3 feat: render exact AR camera textures on the GPU