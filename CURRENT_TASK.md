# Current Task

## Task
Render the exact ARCore camera frame on the GPU in CPU-image coordinates.

## Why
The historical pose pipeline exists, but no camera texture mapping or rendering adapter exists.

## Scope
AR backend, new AR render classes, related JVM/device tests, AR architecture and handoff documents. Preserve all five pre-existing modified files.

## Planned Changes
Capture IMAGE_NORMALIZED to TEXTURE_NORMALIZED mapping with each source frame; reject stale texture references; draw OES into a caller-owned framebuffer without CPU readback.

## Acceptance Criteria
Only the current successfully captured frame can render. Update/pause/close invalidate it. Crop and vertical orientation are explicit and tested. GPU draw works with a synthetic OES texture. No remote spatial clicks enabled before end-to-end frame identity exists.

## Validation
159 JVM tests PASS; Debug/AndroidTest builds and lint PASS. Attached-device synthetic OES GPU corner test PASS. Actual ARCore and remote frame identity remain unverified. Checkpoint commit pending.

## State
DONE
