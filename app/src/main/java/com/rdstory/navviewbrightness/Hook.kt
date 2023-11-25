package com.rdstory.navviewbrightness

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Hook: IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        HomeHook.hook(lpparam)
        SystemUIHook.hook(lpparam)
    }
}