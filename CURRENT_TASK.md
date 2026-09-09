# Current Task

## Task
Reduce network handover recovery latency without replacing PeerConnection or forcing permanent relay.

## Why
User reports several-second Wi-Fi to 5G freeze. Existing detection window starts only after signaling requests return, and candidates/restart adoption can wait for a polling sleep.

## Scope
ICE recovery policy/config, NativeRtcSession, CallViewModel, MediaNegotiator, tests and network design.

## Planned Changes
Record route time at callback; wake signaling on candidates; fast polling during generation adoption; shorter native receiving/backup checks so healthy TURN or cellular paths can win. Keep ALL candidates and short-lived TURN credentials.

## Acceptance Criteria
Stalled signaling does not postpone route grace; no cooldown bypass without a new route; completed media state cannot hide a pending restart; candidates wake exchange; tests/build/lint pass.

## Validation
JVM policy regressions, native config compilation and existing smoke where feasible. Actual Wi-Fi/5G interruption duration requires two-peer device measurement.

## State
DONE

## Result
130 JVM tests passed. assembleDebug and lintDebug passed. Device network interruption timing is not yet measured; no claim of seamless handover.
