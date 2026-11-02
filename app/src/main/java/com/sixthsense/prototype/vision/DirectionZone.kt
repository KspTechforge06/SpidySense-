package com.sixthsense.prototype.vision

/** Image-space direction only; it does not represent distance or safety. */
enum class DirectionZone { LEFT, CENTER, RIGHT, UNKNOWN }

object DirectionZoneMapper {
    fun fromNormalizedCenter(horizontalCenter: Float): DirectionZone = when {
        horizontalCenter < 0f || horizontalCenter > 1f -> DirectionZone.UNKNOWN
        horizontalCenter < 0.33f -> DirectionZone.LEFT
        horizontalCenter <= 0.67f -> DirectionZone.CENTER
        else -> DirectionZone.RIGHT
    }
}
