/*
 * Copyright 2026 Lazy Dog Lab.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.webrtc

/** Call-scoped bridge from camera analysis to the encoder thread. Implementations must be thread safe. */
interface RoiQpMapProvider {
    /** Returns one signed byte per 16x16 block, or null for a neutral map. */
    fun take(timestampNs: Long, width: Int, height: Int): ByteArray?
    fun configured(codecName: String, supported: Boolean)
    fun submitted(codecName: String, active: Boolean)
    fun failed(codecName: String, stage: String)
}
