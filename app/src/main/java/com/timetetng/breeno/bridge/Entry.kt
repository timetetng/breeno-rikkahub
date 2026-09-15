package com.timetetng.breeno.bridge

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed entry point, declared in assets/xposed_init.
 *
 * Runs inside every process the framework injects us into; we only care about Breeno.
 */
class Entry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Config.TARGET_PACKAGE) return

        L.i("injected pkg=${lpparam.packageName} proc=${lpparam.processName} enabled=${Config.ENABLED}")
        if (!Config.ENABLED) return

        try {
            BreenoHook.install(lpparam.classLoader)
        } catch (t: Throwable) {
            L.e("hook install failed", t)
        }
    }
}
