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
import kotlin.math.roundToInt

/**
 * Control brightness using MIUIHome app's NavStubView that located at the bottom of the screen.
 * Long press NavStubView and then move left or right to change brightness.
 */
@SuppressLint("ClickableViewAccessibility")
class NavBrightness(private val navView: FrameLayout) {
    companion object {
        const val TAG = "NavBrightness"
        private const val ACTIVE_TIMEOUT = 500L
        private const val MSG_ADJUST_BRIGHTNESS = 1
        private const val MSG_CHECK_START_TRACKING = 2
        private const val MAX_BRIGHTNESS = 4095f
        private const val MIN_BRIGHTNESS = 1f
    }

    private val touchSlop = ViewConfiguration.get(navView.context).scaledTouchSlop
    private var trackingTouch: Boolean? = null
    private var initX = 0f
    private var initY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastBrightness = 0f
    private var initTime = 0L
    private val speedTracker = SpeedTracker()
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ADJUST_BRIGHTNESS -> {
                    val resolver = navView.context.contentResolver
                    val adj = (msg.obj as Float).coerceIn(-1f, 1f)
                    val newBrightness = (lastBrightness + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * adj)
                        .coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
                    val lastBrightnessInt = lastBrightness.roundToInt()
                    val newBrightnessInt = newBrightness.roundToInt()
                    lastBrightness = newBrightness
                    if (lastBrightnessInt != newBrightnessInt) {
                        putInt(resolver, SCREEN_BRIGHTNESS, newBrightnessInt)
                    }
                }
                MSG_CHECK_START_TRACKING -> checkStartTracking()
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
            val mode = getInt(resolver, SCREEN_BRIGHTNESS_MODE)
            if (mode == SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                // unable to adjust auto brightness yet
                trackingTouch = false
            } else {
                // start tracking gesture
                trackingTouch = true
                lastBrightness = getInt(resolver, SCREEN_BRIGHTNESS).toFloat()
                navView.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                Log.d(TAG, "start tracking brightness gesture")
            }
        }
    }

    private fun clearMessages() {
        handler.removeMessages(MSG_ADJUST_BRIGHTNESS)
        handler.removeMessages(MSG_CHECK_START_TRACKING)
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
            val now = SystemClock.uptimeMillis()
            var startIndex = -1
            var endIndex = -1
            var distance = 0f
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
                distance += (nextInfo.x - point.x).absoluteValue
            }
            val startPoint = pointList.getOrNull(startIndex)
            val endPoint = pointList.getOrNull(endIndex)
            if (startIndex > 0) {
                // remove expired events
                reset(startIndex)
            }
            if (startPoint == null || endPoint == null || startPoint == endPoint) {
                return 0f
            }
            // calculate speed
            return distance / (endPoint.time - startPoint.time).coerceAtLeast(1)
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