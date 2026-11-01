package com.android.systemui.statusbar.quickactions.ui.compose

internal enum class MistBarAlignment {
    CENTER,
    LEFT,
    RIGHT;

    companion object {
        fun fromSetting(value: Int): MistBarAlignment =
            when (value) {
                1 -> LEFT
                2 -> RIGHT
                else -> CENTER
            }
    }
}

internal fun mistBarCutoutMatches(
    alignment: MistBarAlignment,
    centerX: Float,
    windowWidth: Float,
): Boolean {
    if (windowWidth <= 0f) return false
    val fraction = centerX / windowWidth
    return when (alignment) {
        MistBarAlignment.LEFT -> fraction < 0.35f
        MistBarAlignment.RIGHT -> fraction > 0.65f
        MistBarAlignment.CENTER -> fraction in 0.35f..0.65f
    }
}

internal fun mistBarChipLeft(
    alignment: MistBarAlignment,
    windowWidth: Float,
    chipWidth: Float,
    cameraCenterX: Float?,
    cameraGap: Float,
    padding: Float,
    offsetX: Float,
    margin: Float,
): Float {
    val left =
        when (alignment) {
            MistBarAlignment.LEFT ->
                cameraCenterX?.let { it - cameraGap / 2f - padding } ?: margin
            MistBarAlignment.RIGHT ->
                (cameraCenterX?.let { it + cameraGap / 2f + padding } ?: (windowWidth - margin)) -
                    chipWidth
            MistBarAlignment.CENTER -> (cameraCenterX ?: (windowWidth / 2f)) - chipWidth / 2f
        }
    return (left + offsetX).coerceIn(
        margin,
        (windowWidth - margin - chipWidth).coerceAtLeast(margin),
    )
}

internal fun mistBarPopupLeft(
    centerX: Float,
    contentWidth: Float,
    windowWidth: Float,
    margin: Float,
): Float {
    val remaining = (windowWidth - contentWidth).coerceAtLeast(0f)
    val inset = margin.coerceIn(0f, remaining / 2f)
    return (centerX - contentWidth / 2f).coerceIn(inset, remaining - inset)
}
