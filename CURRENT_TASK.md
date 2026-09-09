# Current Task

## Task
Reduce the Wi-Fi to cellular handover interruption and the time the picture stays degraded after it.

## Why
A handover froze the picture for a perceived four to five seconds and then took tens of seconds to look right again. Measurement showed four separate causes, none of them the ICE switch itself.

## Scope
ICE recovery timers and restart decisions, congestion control re-seeding, the video quality ladder after a route change, handover instrumentation, native log hygiene. Preserve existing unstaged UI/AGENTS/camera work.

## Acceptance Criteria
A handover reports a byte gap and a picture gap of the same order; no ICE restart follows a switch that recovered on its own; the re-seeded estimate does not overshoot into the audio reserve; the picture climbs back to the ceiling the previous route held.

## Validation
Measured on the attached device across seven real handovers. Byte gap 2172 -> 1638 ms; picture gap, once measured separately, equals the byte gap rather than exceeding it; no spurious restarts or bandwidth-mode oscillation in the last two captures. 155 JVM tests, assembleDebug and lintDebug PASS.

## State
DONE for the first switch. A second, smaller interruption remains when ICE promotes the cellular relay pair to a direct pair several seconds later.
