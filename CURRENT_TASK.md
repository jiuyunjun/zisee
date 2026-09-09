# Current Task

## Task
Fix video remaining suspended after Wi-Fi/cellular handover.

## Why
AudioBandwidthPolicy paused all video on a single collapsed send estimate and could only resume when both a >=250 kbps estimate and a fresh remote report were present. A handover removes exactly that evidence, so the pause outlived the bad network and survived returning to Wi-Fi.

## Scope
Audio bandwidth policy, RTP sender restoration, report freshness, tests and handoff/network docs. Preserve existing unstaged UI/AGENTS/camera workaround.

## Acceptance Criteria
Pausing needs sustained measured starvation; a new route ignores the stale estimate and retries quickly; a bounded probe fails only on measured failure and backs off to at most 30 s without ever stopping; a rejected setParameters cannot strand the camera at the probe format; user-disabled camera stays off.

## Validation
143 JVM tests PASS (new: missing evidence, stale reports, route change, probe backoff). :app:assembleDebug and :app:lintDebug PASS. Debug APK installed on the attached device. Two-device handover measurement still outstanding.

## State
DONE (6d2d0a3); pending real two-device handover measurement.
