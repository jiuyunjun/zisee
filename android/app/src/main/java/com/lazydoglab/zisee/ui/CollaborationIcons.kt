package com.lazydoglab.zisee.ui

/** Shared 24-unit glyphs for Compose AR tools and native cross-app guidance controls. */
internal fun collaborationIconPath(icon: String): String? = when (icon) {
    "ar-pin" -> "M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8M12 12v8M8 21h8"
    "ar-arrow" -> "M5 19 19 5M8 5h11v11"
    "ar-circle" -> "M20 12a8 8 0 1 0-16 0a8 8 0 1 0 16 0"
    "ar-pen" -> "M4 20l1-5L17 3l4 4L9 19zM14 6l4 4M4 20l5-1"
    "ar-undo" -> "M8 5 3 10l5 5M3 10h11a6 6 0 0 1 0 12"
    "ar-trash" -> "M4 6h16M9 3h6M6 6l1 15h10l1-15M10 10v7M14 10v7"
    "ar-stop" -> "M6 5h12q1 0 1 1v12q0 1-1 1H6q-1 0-1-1V6q0-1 1-1z"
    "guide-browse" -> "M5 3l14 10-7 1-3 7z"
    "guide-number" -> "M9 3 7 21M17 3l-2 18M4 9h17M3 15h17"
    "guide-eye" -> "M2 12q10-14 20 0q-10 14-20 0M15 12a3 3 0 1 0-6 0a3 3 0 1 0 6 0"
    "guide-eye-off" -> "M3 3l18 18M2 12q4-6 8-6M14 6q5 1 8 6q-2 3-4 4M14 18q-7 2-12-6"
    "guide-pause" -> "M8 5v14M16 5v14"
    "guide-play" -> "M7 4l13 8-13 8z"
    "guide-check" -> "M4 12l5 5L20 6"
    "guide-close" -> "M6 6l12 12M18 6 6 18"
    "guide-share" -> "M4 3h16v14H4zM8 21h8M12 17v4M8 9l4-3 4 3M12 6v7"
    "guide-home" -> "M3 11l9-8 9 8M5 10v11h5v-7h4v7h5V10"
    "guide-ui" -> "M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5M7 8h10v8H7z"
    else -> null
}
