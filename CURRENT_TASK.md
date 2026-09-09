# Current Task

## Task
Connect AR protocol to a real per-call reliable ordered DataChannel and owner-thread controller adapter.

## Why
AR framework currently has only a codec; no transport, session admission, bounded command handling or RTC cleanup integration.

## Scope
ar/collaboration, ArProtocol, NativeRtcSession, regression tests, AR architecture and checkpoint records.

## Planned Changes
Negotiated channel id 2 (camera-state retains id 0); ready/join/joined/leave/ended handshake; explicit local controller attachment and remote opt-in; result correlation; bounded receive queue/rate and send backpressure; GL-dispatched marker commands; cleanup on channel close/hangup.

## Acceptance Criteria
Unknown/stale sessions cannot mutate AR; remote ready never starts a camera; commands remain ordered/bounded; send failure never reports success; RTC media survives AR failure. Real paired data channels pass smoke test if feasible.

## Validation
JVM collaboration/protocol tests, native data-channel smoke, build/lint. Actual AR camera/rendering/frame synchronization remains future integration and must not be advertised as complete.

## State
DONE

## Result
139 JVM tests, debug/test APK builds and lint PASS. Paired native AR SCTP/DTLS smoke PASS including malformed-payload isolation. Existing native camera/ICE/encode/decode/restart/release smoke PASS. Latest debug APK installed. Real AR capture/rendering/video frame synchronization and Wi-Fi/5G interruption measurement remain unverified.
