package com.rdstory.navviewbrightness

import android.widget.FrameLayout
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


class Hook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
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
    }
}