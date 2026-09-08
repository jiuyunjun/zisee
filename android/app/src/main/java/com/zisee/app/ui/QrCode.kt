package com.zisee.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Draws [content] as a QR code, one rectangle per module.
 *
 * Colours are fixed rather than themed: a scanner needs dark-on-light contrast, so following the
 * app into dark mode would make the code unreadable. Zero requested dimensions ask the writer for
 * the natural module matrix, which is then scaled to whatever the layout gives it.
 */
@Composable
fun QrCode(content: String, modifier: Modifier) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                ),
            )
        }.getOrNull()
    } ?: return
    Canvas(modifier) {
        val modules = matrix.width
        val cell = size.minDimension / modules
        drawRect(Color.White, size = Size(cell * modules, cell * modules))
        for (row in 0 until matrix.height) {
            for (column in 0 until modules) {
                if (matrix.get(column, row)) {
                    drawRect(Color.Black, Offset(column * cell, row * cell), Size(cell, cell))
                }
            }
        }
    }
}
