package com.timetetng.breeno.bridge

import android.util.Log

/**
 * Tiny logging shim.
 *
 * Everything the module does on the hook side ends up in logcat under the target app's
 * process, which is the only realistic way to debug an Xposed module. Tag is deliberately
 * short so `adb logcat -s` / `grep` stays readable.
 */
object L {
    const val TAG = "BrenoRikka"

    @Volatile
    var verbose: Boolean = true

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
    }

    fun d(msg: String) {
        if (verbose) Log.d(TAG, msg)
    }
}
