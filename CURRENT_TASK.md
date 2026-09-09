# Current Task

## Task
Integrate exclusive AR camera ownership and retained GPU frames into the existing rear WebRTC track.

## Why
The AR framework currently has no media owner; mutable OES textures cannot cross an asynchronous encoder boundary.

## Scope
AR media adapters, NativeRtcSession integration, focused tests and architecture/checkpoint documents. Preserve the five pre-existing edits (including the concurrent targetRotation hunk).

## Planned Changes
Add a bounded RGB framebuffer pool on a dedicated EGL worker, controller endpoint ownership, explicit start/stop APIs and camera restoration. Then implement encoded-content identity correlation and surface-latched frame references as a separate checkpoint.

## Acceptance Criteria
AR starts only after ordinary capture closes, delivers independent video_back frames, drops under pool pressure, and releases GL only after retained buffers return. Failure/exit/hangup must clean up and restore capture when the call remains active.

## Validation
JVM tests, Debug and AndroidTest builds, lint; available device instrumentation. Physical ARCore and two-device behavior must be reported separately.

## State
IMPLEMENTING