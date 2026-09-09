# Current Task

## Task
Carry AR source identity inside H264 access units and resolve the identity of the SurfaceTexture-latched frame.

## Why
Capture/decoder clocks and a latest DataChannel timestamp cannot identify the displayed historical AR image.

## Scope
AR identity protocol, codec adapters, GPU frame tags, VideoFeed/TextureViewRenderer, tests and architecture documents.

## Planned Changes
Versioned unregistered-user-data SEI (session UUID + source timestamp), bounded codec correlation, texture tags surviving full-frame scaling, exact surface timestamp lookup. Preserve default codec availability and fail closed when identity cannot survive the selected codec/path.

## Acceptance Criteria
Reordering, drops, invalid/duplicate metadata and unknown frame timestamps never guess a reference. UI receives only a source reference matched to the actual latched texture with that frame geometry. No pixel readback for synchronization.

## Validation
JVM parser/state tests, builds/lint, native encode/decode/renderer smoke where available.

## State
DONE

## Notes
Previous checkpoint fe4ecc4: 159 JVM tests, builds/lint and connected-device arFramePool PASS. Pre-existing five-file edits remain unstaged.
Identity implementation verified by 162 JVM tests, builds/lint and real H264 codec loopback. Surface display verification remains blocked by MIUI background Activity launch denial; see HANDOFF.md.
