package com.sixthsense.prototype.navigation

import com.sixthsense.prototype.vision.DirectionZone

enum class HapticCommand { LEFT, CENTER, RIGHT, ALL, STOP }

object HapticCommandMapper {
    fun forZone(zone: DirectionZone): HapticCommand? = when (zone) {
        DirectionZone.LEFT -> HapticCommand.LEFT
        DirectionZone.CENTER -> HapticCommand.CENTER
        DirectionZone.RIGHT -> HapticCommand.RIGHT
        DirectionZone.UNKNOWN -> null
    }
}
