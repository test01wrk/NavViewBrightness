package com.rdstory.navviewbrightness

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


class Hook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.miui.home") {
            return
        }
        XposedHelpers.findAndHookConstructor(
            "com.miui.home.recents.NavStubView",
            lpparam.classLoader,
            Context::class.java,
            object : XC_MethodHook() {
                @SuppressLint("ClickableViewAccessibility")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val navView = param.thisObject as FrameLayout
                    val navBrightness = NavBrightness(navView)
                    navView.setOnTouchListener { _, event ->
                        return@setOnTouchListener navBrightness.onTouchEvent(event)
                    }
                }
            })
    }
}