package com.miui.appvolumebar

import com.miui.appvolumebar.misound.MiSoundHooker
import com.miui.appvolumebar.systemui.SystemUiHooker
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "AppVolumeBarHook"
        const val PKG_SYSTEMUI = "com.android.systemui"
        const val PKG_PLUGIN = "miui.systemui.plugin"
        const val PKG_MISOUND = "com.miui.misound"
        const val ACTION_EXPAND_MEDIA_VOLUME = "com.miui.appvolumebar.ACTION_EXPAND_MEDIA_VOLUME"

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
        }

        fun log(msg: String, tr: Throwable?) {
            if (tr != null) {
                XposedBridge.log("[$TAG] $msg\n" + android.util.Log.getStackTraceString(tr))
            } else {
                XposedBridge.log("[$TAG] $msg")
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            PKG_MISOUND -> {
                log("Handling loadPackage for: ${lpparam.packageName}")
                MiSoundHooker.init(lpparam)
            }
            PKG_SYSTEMUI -> {
                log("Handling loadPackage for: ${lpparam.packageName}")
                SystemUiHooker.init(lpparam)
            }
            PKG_PLUGIN -> {
                log("Handling loadPackage for: ${lpparam.packageName} (Direct Plugin Scope)")
                SystemUiHooker.initPlugin(lpparam.classLoader)
            }
        }
    }
}

