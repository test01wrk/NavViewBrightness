package com.rdstory.navviewbrightness

import kotlin.math.roundToInt

object BrightnessUtils {
    fun convertGammaToLinearFloat(i: Int, min: Float, max: Float): Float {
        val norm: Float = MathUtils.norm(0.0f, 65535.0f, i.toFloat())
        val f3 = if (norm <= 0.5f) {
            MathUtils.sq(norm / 0.5f)
        } else {
            MathUtils.exp((norm - 0.5599107f) / 0.17883277f) + 0.28466892f
        }
        return MathUtils.lerp(min, max, MathUtils.constrain(f3, 0.0f, 12.0f) / 12.0f)
    }

    fun convertLinearToGammaFloat(f: Float, min: Float, max: Float): Int {
        val norm: Float = MathUtils.norm(min, max, f) * 12.0f
        val f4 = if (norm <= 1.0f) {
            MathUtils.sqrt(norm) * 0.5f
        } else {
            MathUtils.log(norm - 0.28466892f) * 0.17883277f + 0.5599107f
        }
        return MathUtils.lerp(0.0f, 65535.0f, f4).roundToInt()
    }
}

object MathUtils {

    fun constrain(amount: Float, low: Float, high: Float): Float {
        return if (amount < low) low else if (amount > high) high else amount
    }

    fun log(a: Float): Float {
        return kotlin.math.ln(a.toDouble()).toFloat()
    }

    fun exp(a: Float): Float {
        return kotlin.math.exp(a.toDouble()).toFloat()
    }

    fun sqrt(a: Float): Float {
        return kotlin.math.sqrt(a.toDouble()).toFloat()
    }

    fun sq(v: Float): Float {
        return v * v
    }

    fun lerp(start: Float, stop: Float, amount: Float): Float {
        return start + (stop - start) * amount
    }

    fun norm(start: Float, stop: Float, value: Float): Float {
        return (value - start) / (stop - start)
    }

}