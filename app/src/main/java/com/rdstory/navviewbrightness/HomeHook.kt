package com.rdstory.navviewbrightness

import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


object HomeHook {
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.miui.home") {
            return
        }
        // hook miui home NavStubView constructor
        XposedHelpers.findClass(
            "com.miui.home.recents.NavStubView",
            lpparam.classLoader
        ).declaredConstructors.reduce { acc, constructor ->
            if (acc.parameterCount > constructor.parameterCount) acc else constructor
        }.let { constructor ->
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    NavBrightness(param.thisObject as FrameLayout)
                }
            })
        }

        // hide gesture line in landscape
        XposedHelpers.findAndHookMethod(
            "com.miui.home.recents.NavStubView",
            lpparam.classLoader,
            "getHotSpaceHeight",
            object : XC_MethodHook() {
                private var hideLine = 0
                private var wasLandscape = false
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = (param.thisObject as View).context
                    val isLandscape = XposedHelpers.callMethod(
                        param.thisObject, "isLandScapeActually") as Boolean
                    if (!wasLandscape && !isLandscape) {
                        hideLine = Settings.Global.getInt(context.contentResolver, "hide_gesture_line", 0)
                    }
                    wasLandscape = isLandscape
                    Settings.Global.putInt(context.contentResolver, "hide_gesture_line", if (isLandscape) 1 else hideLine)
                }
            })
    }
}