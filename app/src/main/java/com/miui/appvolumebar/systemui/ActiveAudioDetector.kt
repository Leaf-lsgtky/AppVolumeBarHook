package com.miui.appvolumebar.systemui

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.text.TextUtils
import android.util.Log
import com.miui.appvolumebar.MainHook
import java.lang.reflect.Method

/**
 * 媒体播放状态探测器。
 * 严格对齐 com.miui.misound 内部 AbstractC1034f.m915a 的官方判断逻辑：
 * 1. UID >= 10000（第三方应用）
 * 2. 过滤掉动态壁纸（com.miui.miwallpaper）
 * 3. 播放状态为 PLAYER_STATE_STARTED (2)
 * 4. 音频属性 usage == USAGE_MEDIA (1) 或 volumeControlStream == STREAM_MUSIC (3)
 */
object ActiveAudioDetector {

    fun hasActiveMediaPlayback(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val packageManager = context.packageManager
            val activeConfigs = audioManager.activePlaybackConfigurations
            if (activeConfigs.isNullOrEmpty()) {
                MainHook.log("ActiveAudioDetector: No activePlaybackConfigurations reported")
                return false
            }

            for (config in activeConfigs) {
                if (isTargetMediaConfig(config, packageManager)) {
                    return true
                }
            }
            MainHook.log("ActiveAudioDetector: ${activeConfigs.size} configs checked, no active media stream found")
            false
        } catch (t: Throwable) {
            MainHook.log("ActiveAudioDetector error checking configurations", t)
            false
        }
    }

    private fun isTargetMediaConfig(
        config: AudioPlaybackConfiguration,
        packageManager: PackageManager
    ): Boolean {
        try {
            val cls: Class<*> = config.javaClass
            val getClientUidMethod: Method = cls.getDeclaredMethod("getClientUid")
            getClientUidMethod.isAccessible = true
            val uid = (getClientUidMethod.invoke(config) as? Int) ?: return false

            if (uid < 10000) {
                return false
            }

            val pkgName = runCatching { packageManager.getNameForUid(uid) }.getOrNull()
            if (!TextUtils.isEmpty(pkgName) && pkgName == "com.miui.miwallpaper") {
                return false
            }

            val getPlayerStateMethod: Method = cls.getDeclaredMethod("getPlayerState")
            getPlayerStateMethod.isAccessible = true
            val playerState = (getPlayerStateMethod.invoke(config) as? Int) ?: 0

            val isActive = runCatching {
                cls.getMethod("isActive").invoke(config) as? Boolean
            }.getOrNull() == true

            // 2 = AudioPlaybackConfiguration.PLAYER_STATE_STARTED
            val isStarted = playerState == 2 || isActive
            if (!isStarted) {
                return false
            }

            val audioAttributes = config.audioAttributes ?: return false
            val usage = audioAttributes.usage
            val volumeStream = audioAttributes.volumeControlStream

            // usage == 1 (USAGE_MEDIA) || volumeStream == 3 (STREAM_MUSIC)
            val isMedia = usage == 1 || volumeStream == 3
            if (isMedia) {
                MainHook.log("ActiveAudioDetector: Found media playback! pkg=$pkgName, uid=$uid, state=$playerState, isActive=$isActive, usage=$usage, stream=$volumeStream")
            }
            return isMedia
        } catch (t: Throwable) {
            return try {
                val getUid = config.javaClass.getMethod("getClientUid")
                val uid = getUid.invoke(config) as? Int ?: -1
                val isActive = runCatching {
                    config.javaClass.getMethod("isActive").invoke(config) as? Boolean
                }.getOrNull() == true
                if (uid >= 10000 && isActive) {
                    val attrs = config.audioAttributes
                    val isMedia = attrs != null && (attrs.usage == 1 || attrs.volumeControlStream == 3)
                    if (isMedia) {
                        MainHook.log("ActiveAudioDetector (fallback): Found media playback for uid=$uid")
                    }
                    isMedia
                } else false
            } catch (_: Throwable) {
                false
            }
        }
    }
}

