package com.rdstory.navviewbrightness

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings.System.*
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import de.robv.android.xposed.XposedHelpers
import kotlin.math.abs

class NavBrightness(private val navView: View) {
    companion object {
        const val TAG = "NavBrightness"
        private const val ACTIVE_TIMEOUT = 500L
        private const val MSG_ADJUST_BRIGHTNESS = 1
        private const val MSG_CHECK_START_TRACKING = 2
        private const val MAX_BRIGHTNESS = 2047
        private const val MIN_BRIGHTNESS = 8
    }

    private val touchSlop = ViewConfiguration.get(navView.context).scaledTouchSlop
    private var trackingTouch: Boolean? = null
    private var initX = 0f
    private var initY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var initBrightness = 0
    private var initTime = 0L
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ADJUST_BRIGHTNESS -> {
                    val resolver = navView.context.contentResolver
                    val adj = (msg.obj as Float).coerceIn(-1f, 1f)
                    val newBrightness = (initBrightness + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * adj)
                        .toInt().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
                    putInt(resolver, SCREEN_BRIGHTNESS, newBrightness)
                }
                MSG_CHECK_START_TRACKING -> checkStartTracking()
            }
        }
    }

    private fun checkStartTracking() {
        if (trackingTouch != null) {
            return
        }
        if (SystemClock.uptimeMillis() - initTime < ACTIVE_TIMEOUT) {
            trackingTouch = false
        } else if (XposedHelpers.getObjectField(navView, "mDownEvent") == null) {
            val resolver = navView.context.contentResolver
            val mode = getInt(resolver, SCREEN_BRIGHTNESS_MODE)
            if (mode == SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                trackingTouch = false
            } else {
                trackingTouch = true
                initX = lastX
                initY = lastY
                initBrightness = getInt(resolver, SCREEN_BRIGHTNESS)
                navView.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                Log.d(TAG, "start tracking brightness gesture")
            }
        }
    }

    private fun clearMessages() {
        handler.removeMessages(MSG_ADJUST_BRIGHTNESS)
        handler.removeMessages(MSG_CHECK_START_TRACKING)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                trackingTouch = null
                initX = event.x
                initY = event.y
                lastX = initX
                lastY = initY
                initBrightness = 0
                initTime = SystemClock.uptimeMillis()
                clearMessages()
                handler.sendEmptyMessageDelayed(MSG_CHECK_START_TRACKING, ACTIVE_TIMEOUT)
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (trackingTouch == true && abs(event.y - initY) >= 8 * touchSlop) {
                    trackingTouch = false
                } else if (trackingTouch == null && abs(event.y - initY) >= touchSlop) {
                    trackingTouch = false
                } else if (trackingTouch == null && abs(event.x - initX) >= touchSlop) {
                    checkStartTracking()
                }
                if (trackingTouch == true) {
                    val adj = (event.x - initX) / (navView.width * 0.88f)
                    handler.obtainMessage(MSG_ADJUST_BRIGHTNESS, adj).sendToTarget()
                }
            }
            MotionEvent.ACTION_UP -> clearMessages()
        }
        return false
    }

}