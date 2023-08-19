package com.rdstory.navviewbrightness

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings.System.*
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import de.robv.android.xposed.XposedHelpers
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Control brightness using MIUIHome app's NavStubView that located at the bottom of the screen.
 * Long press NavStubView and then move left or right to change brightness.
 */
@SuppressLint("ClickableViewAccessibility")
class NavBrightness(private val navView: FrameLayout) {
    companion object {
        const val TAG = "NavBrightness"
        private const val MSG_ADJUST_BRIGHTNESS = 1
        private const val MSG_CHECK_START_TRACKING = 2
        private const val MSG_TOGGLE_AUTO_BRIGHTNESS = 3
        private const val ACTIVE_TIMEOUT = 500L
        private const val MAX_BRIGHTNESS = 4095f
        private const val MIN_BRIGHTNESS = 1f
        private const val TOGGLE_AUTO_BRIGHTNESS_SPEED = 2f
    }

    private val touchSlop = ViewConfiguration.get(navView.context).scaledTouchSlop
    private var trackingTouch: Boolean? = null
    private var initX = 0f
    private var initY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastBrightness = 0f
    private var brightnessMode: Int? = null
    private var initTime = 0L
    private val speedTracker = SpeedTracker()
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ADJUST_BRIGHTNESS -> run {
                    if (brightnessMode != SCREEN_BRIGHTNESS_MODE_MANUAL) {
                        // unable to adjust auto brightness yet
                        return@run
                    }
                    val gamma = ((0.02 * lastBrightness + 1.4).pow(2.8) / 50).coerceIn(0.01, 100.0)
                    val adj = (msg.obj as Float * gamma.toFloat()).coerceIn(-1f, 1f)
                    val newBrightness = (lastBrightness + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * adj)
                        .coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
                    val lastBrightnessInt = lastBrightness.roundToInt()
                    val newBrightnessInt = newBrightness.roundToInt()
                    if (BuildConfig.DEBUG) {
                        Log.i("NavBrightness", "adj=${adj}, gama=${gamma}, " +
                                "new=${newBrightnessInt}, last=${lastBrightnessInt}")
                    }
                    lastBrightness = newBrightness
                    if (lastBrightnessInt != newBrightnessInt) {
                        val resolver = navView.context.contentResolver
                        putInt(resolver, SCREEN_BRIGHTNESS, newBrightnessInt)
                    }
                }
                MSG_CHECK_START_TRACKING -> checkStartTracking()
                MSG_TOGGLE_AUTO_BRIGHTNESS -> {
                    val resolver = navView.context.contentResolver
                    val mode = getInt(resolver, SCREEN_BRIGHTNESS_MODE)
                    val newMode = if (mode == SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                        SCREEN_BRIGHTNESS_MODE_MANUAL else SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    putInt(resolver, SCREEN_BRIGHTNESS_MODE, newMode)
                    for (i in 0..newMode) {
                        navView.postDelayed({
                            navView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        }, 150L * (i + 1))
                    }
                }
            }
        }
    }

    init {
        // register touch listener to detect gesture
        navView.setOnTouchListener { _, event ->
            return@setOnTouchListener onTouchEvent(event)
        }
    }

    private fun checkStartTracking() {
        if (trackingTouch != null) {
            return
        }
        if (SystemClock.uptimeMillis() - initTime < ACTIVE_TIMEOUT) {
            // user moved too soon, abort gesture detection
            trackingTouch = false
        } else if (XposedHelpers.getObjectField(navView, "mDownEvent") == null) {
            // A null mDownEvent means that NavStubView is not consuming touch event,
            // we don't want to break NavStubView's original functionality
            val resolver = navView.context.contentResolver
            trackingTouch = true
            brightnessMode = getInt(resolver, SCREEN_BRIGHTNESS_MODE)
            lastBrightness = getInt(resolver, SCREEN_BRIGHTNESS).toFloat()
            navView.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            Log.d(TAG, "start tracking brightness gesture")
        }
    }

    private fun clearMessages() {
        handler.removeMessages(MSG_ADJUST_BRIGHTNESS)
        handler.removeMessages(MSG_CHECK_START_TRACKING)
        handler.removeMessages(MSG_TOGGLE_AUTO_BRIGHTNESS)
    }

    private fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                trackingTouch = null
                initX = event.x
                initY = event.y
                lastX = initX
                lastY = initY
                lastBrightness = 0f
                initTime = SystemClock.uptimeMillis()
                clearMessages()
                speedTracker.reset()
                handler.sendEmptyMessageDelayed(MSG_CHECK_START_TRACKING, ACTIVE_TIMEOUT)
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingTouch == null && abs(event.y - initY) >= touchSlop) {
                    trackingTouch = false
                } else if (trackingTouch == null && abs(event.x - initX) >= touchSlop) {
                    checkStartTracking()
                }
                if (trackingTouch == true) {
                    speedTracker.addMotionEvent(event)
                    // calculate adjustment value, increase or decrease according to moving speed
                    val speedXScale = speedTracker.getNormalizedSpeedX().coerceIn(0.2f, 5f)
                    val adj = (event.x - lastX) / navView.width * speedXScale
                    handler.obtainMessage(MSG_ADJUST_BRIGHTNESS, adj).sendToTarget()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                clearMessages()
                if (trackingTouch == true) {
                    speedTracker.addMotionEvent(event)
                    val speedYScale = speedTracker.getNormalizedSpeedY().coerceIn(0.2f, 10f)
                    if (speedYScale >= TOGGLE_AUTO_BRIGHTNESS_SPEED) {
                        handler.sendEmptyMessage(MSG_TOGGLE_AUTO_BRIGHTNESS)
                    }
                }
                speedTracker.reset()
            }
        }
        return false
    }

    /**
     * Simple moving speed tracker
     */
    private class SpeedTracker {
        companion object {
            const val TRACK_TIME = 300
            const val NORMALIZE_FACTOR = 2f
            const val POOL_SIZE = TRACK_TIME / 8
        }

        class MotionPoint {
            var x = 0f
            var y = 0f
            var time = 0L
        }

        private val recyclePool = ArrayDeque<MotionPoint>()
        private val pointList = ArrayDeque<MotionPoint>()
        private val outSpeed = arrayOf(0f, 0f)

        fun addMotionEvent(event: MotionEvent) {
            pointList.addLast((recyclePool.removeLastOrNull() ?: MotionPoint()).apply {
                x = event.x
                y = event.y
                time = event.eventTime
            })
        }

        fun getNormalizedSpeedX(): Float {
            return getSpeedX() / NORMALIZE_FACTOR
        }

        fun getSpeedX(): Float {
            getSpeed(outSpeed)
            return outSpeed[0]
        }

        fun getNormalizedSpeedY(): Float {
            return getSpeedY() / NORMALIZE_FACTOR
        }

        fun getSpeedY(): Float {
            getSpeed(outSpeed)
            return outSpeed[1]
        }

        fun getSpeed(outSpeed: Array<Float>) {
            outSpeed[0] = 0f
            outSpeed[1] = 0f
            val now = SystemClock.uptimeMillis()
            var startIndex = -1
            var endIndex = -1
            var distanceX = 0f
            var distanceY = 0f
            // calculate recent moving distance
            pointList.forEachIndexed { index, point ->
                if (now - point.time > TRACK_TIME) {
                    // skip expired events
                    startIndex = index + 1
                    return@forEachIndexed
                }
                if (startIndex < 0) {
                    startIndex = index
                }
                val nextInfo = pointList.getOrNull(index + 1) ?: let {
                    endIndex = index
                    return@forEachIndexed
                }
                distanceX += (nextInfo.x - point.x).absoluteValue
                distanceY += (nextInfo.y - point.y).absoluteValue
            }
            val startPoint = pointList.getOrNull(startIndex)
            val endPoint = pointList.getOrNull(endIndex)
            if (startIndex > 0) {
                // remove expired events
                reset(startIndex)
            }
            if (startPoint == null || endPoint == null || startPoint == endPoint) {
                return
            }
            // calculate speed
            val time = (endPoint.time - startPoint.time).coerceAtLeast(1)
            outSpeed[0] = distanceX / time
            outSpeed[1] = distanceY / time
        }

        fun reset(clearSize: Int = pointList.size) {
            for (i in 0 until clearSize) {
                val point = pointList.removeFirstOrNull() ?: continue
                if (recyclePool.size < POOL_SIZE) {
                    point.x = 0f
                    point.y = 0f
                    point.time = 0L
                    recyclePool.addLast(point)
                }
            }
        }
    }
}