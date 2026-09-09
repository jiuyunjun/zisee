# Current Task

## Task
Fix Show Me front/back rotation at the CameraX-to-WebRTC texture boundary.

## Why
User screenshot shows both local concurrent cameras sideways while the peer is upright.

## Scope
DualCameraCapture, texture transform helper/tests, orientation documentation, task records.
Existing AGENTS.md, NativeRtcSession and UI/test edits belong to previous work; preserve and do not stage them.

## Planned Changes
Undo camera SurfaceTexture sensor rotation and front mirror before publishing frame rotation, following WebRTC Camera2Session. Respect hasCameraTransform and wait for transformation metadata.

## Acceptance Criteria
One sensor rotation application, unmirrored outbound media, unchanged dimensions/timestamps, balanced buffer references; build and relevant tests pass.

## Validation
Android unit tests, debug APK build, lint, Android matrix regression test if feasible. Real two-peer visual validation remains required.

## State
DONE

## Result
127 JVM tests passed; debug and test APK builds and lint passed. Android Matrix smoke passed on attached device. Updated debug APK installed. Real camera/two-peer visual validation still pending.
