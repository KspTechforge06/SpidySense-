package com.sixthsense.prototype.navigation

import com.sixthsense.prototype.vision.DirectionZone

/** Physical motor locations around the wearable: left, front, right, and back. */
enum class HapticCommand { LEFT, FRONT, RIGHT, BACK, ALL, STOP }

object HapticCommandMapper {
    fun forZone(zone: DirectionZone): HapticCommand? = when (zone) {
        DirectionZone.LEFT -> HapticCommand.LEFT
        // The camera's centre image zone corresponds to the physical front motor.
        DirectionZone.CENTER -> HapticCommand.FRONT
        DirectionZone.RIGHT -> HapticCommand.RIGHT
        DirectionZone.UNKNOWN -> null
    }
}
