package com.rdstory.navviewbrightness

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


object SystemUIHook {
    private const val SYSTEMUI_PKG = "com.android.systemui"
    private const val SETTINGS_PKG = "com.android.settings"
    const val BRIDGE_BRIGHTNESS = "__screen_brightness_bridge__"
    const val BRIDGE_BRIGHTNESS_TMP = "__screen_brightness_bridge_temp__"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (
            !(lpparam.packageName == SYSTEMUI_PKG && lpparam.processName == SYSTEMUI_PKG) &&
            !(lpparam.packageName == SETTINGS_PKG && lpparam.processName == SETTINGS_PKG)
        ) {
            return
        }

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.thisObject as Context
                    initBrightness(context)
                }
            })
    }

    private fun initBrightness(context: Context) {
        val displayManager = context.applicationContext
            .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displayId = context.display?.displayId
            ?: XposedHelpers.callMethod(context, "getDisplayId") as Int
        val brightness = XposedHelpers.callMethod(displayManager, "getBrightness", displayId) as Float
        val settingBrightness = Settings.System.getFloat(context.contentResolver, BRIDGE_BRIGHTNESS, -1f)
        if (settingBrightness !in 0f..1f && brightness in 0f..1f) {
            Settings.System.putFloat(context.contentResolver, BRIDGE_BRIGHTNESS, brightness)
        }
        fun setBrightness(settingsKey: String) {
            val value = Settings.System.getFloat(context.contentResolver, settingsKey, -1f)
            if (value in 0f..1f) {
                val method = if (settingsKey == BRIDGE_BRIGHTNESS) "setBrightness" else "setTemporaryBrightness"
                XposedHelpers.callMethod(displayManager, method, displayId, value)
            }
            if (BuildConfig.DEBUG) {
                XposedBridge.log("Settings brightness: $settingsKey => $value")
            }
        }
        val handler = Handler(Looper.getMainLooper())
        arrayOf(BRIDGE_BRIGHTNESS_TMP, BRIDGE_BRIGHTNESS).forEach { key ->
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        setBrightness(key)
                    }
                }
            )
        }
    }

}