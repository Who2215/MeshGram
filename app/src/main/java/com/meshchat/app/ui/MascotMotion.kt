package com.meshchat.app.ui

import kotlin.math.sin

data class MascotPose(val x: Float = 0f, val y: Float = 0f, val tilt: Float = 0f,
    val action: Float = 0f, val reaction: Float = 0f)

object MascotMotion {
    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    fun pose(id: String, progress: Float): MascotPose {
        val p = progress.coerceIn(0f, 1f)
        if (p == 0f || p == 1f) return MascotPose()
        val anticipation = smooth(p / .18f) * (1 - smooth((p - .18f) / .15f))
        val action = smooth((p - .18f) / .18f) * (1 - smooth((p - .68f) / .25f))
        val reaction = smooth((p - .4f) / .1f) * (1 - smooth((p - .68f) / .2f))
        val wobble = sin(p * 6.283185f * 5) * reaction
        return when (id) {
            "laugh" -> MascotPose(4 * wobble, -3 * reaction, -5 * anticipation + 23 * action + 5 * wobble, action, reaction)
            "facepalm" -> MascotPose(y = 4 * reaction, tilt = -8 * anticipation + 10 * reaction, action = action, reaction = reaction)
            "popcorn" -> MascotPose(tilt = -3 * anticipation + 4 * reaction, action = action, reaction = (1 + sin(p * 90)) / 2 * reaction)
            "coffee" -> MascotPose(y = -7 * reaction, tilt = -6 * action + 14 * reaction, action = action, reaction = reaction)
            "dance" -> MascotPose(7 * wobble, -9 * kotlin.math.abs(wobble), 12 * wobble, action, reaction)
            "rage" -> MascotPose(3 * wobble, 5 * anticipation - 6 * reaction, 3 * wobble, action, reaction)
            else -> MascotPose(y = sin(p * 6.283185f) * 3, action = action, reaction = reaction)
        }
    }
}
