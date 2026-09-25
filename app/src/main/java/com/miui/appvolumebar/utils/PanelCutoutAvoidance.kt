package com.miui.appvolumebar.utils

import android.content.res.Configuration
import android.os.Build
import android.view.View
import com.miui.appvolumebar.MainHook

/**
 * 横屏挖孔避让。
 *
 * 挖孔屏横过来之后，前置摄像头落在**某一条短边的垂直居中位置**；而分应用音量面板
 * 的定位恰好是「贴 END 侧 + 垂直居中」（见 MiSoundHooker.setupCardLayout），
 * 两者高度重合，横屏时面板会被挖孔挖掉一块。
 *
 * 处理办法：横屏且挖孔落在面板这一侧时，把面板的 marginEnd 加大到「挖孔内缘 + 间隙」，
 * 也就是把面板朝屏幕中心横向推开；挖孔在对侧时保持原样。
 *
 * 判据直接用 DisplayCutout 给出的挖孔真实坐标，而不是去猜
 * Surface.ROTATION_90 / ROTATION_270 到底对应哪只手 —— 那个映射各家实现和文档说法
 * 并不一致，而挖孔坐标在当前方向下是绝对准确的，两种横屏方向都能自动覆盖。
 */
object PanelCutoutAvoidance {

    private const val TAG = "PanelCutoutAvoidance"

    /** 面板边缘与挖孔内缘之间额外留出的呼吸空间 */
    private const val EXTRA_GAP_DP = 8f

    /**
     * 计算面板实际应使用的 marginEnd。
     *
     * @param anchor 面板所在窗口里的任意 View（用来取 WindowInsets 与屏幕宽度）
     * @param baseMarginPx 竖屏/无需避让时的原始边距
     * @return 需要避让时返回更大的边距，否则返回 [baseMarginPx]
     */
    fun resolveEndMarginPx(anchor: View, baseMarginPx: Int): Int {
        return try {
            val res = anchor.context?.resources ?: return baseMarginPx
            // 挖孔只在横屏才会挡住面板；竖屏时挖孔在顶部，与面板无关。
            if (res.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                return baseMarginPx
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return baseMarginPx

            // 面板窗口是全屏的，窗口坐标系与屏幕坐标系一致。
            val width = anchor.rootView?.width?.takeIf { it > 0 }
                ?: res.displayMetrics.widthPixels
            if (width <= 0) return baseMarginPx

            val cutout = (anchor.rootWindowInsets ?: anchor.rootView?.rootWindowInsets)
                ?.displayCutout
            if (cutout == null) {
                MainHook.log("$TAG: 横屏但拿不到 DisplayCutout，本次不避让")
                return baseMarginPx
            }
            val rects = cutout.boundingRects
            if (rects.isEmpty()) {
                MainHook.log("$TAG: 横屏但 DisplayCutout 为空（窗口未声明允许延伸到挖孔区），本次不避让")
                return baseMarginPx
            }

            // 面板贴 END 侧：LTR 时在右、RTL 时在左。
            // 只有挖孔也落在这一侧才需要让位，挖孔在对侧时面板本来就没事。
            val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val innerEdgePx = if (rtl) {
                // 挖孔在左：内缘是 rect.right，到左边缘的距离就是 rect.right
                val hole = rects.minByOrNull { it.left } ?: return baseMarginPx
                if (hole.left >= width / 2) return baseMarginPx
                hole.right
            } else {
                // 挖孔在右：内缘是 rect.left，到右边缘的距离是 width - rect.left
                val hole = rects.maxByOrNull { it.right } ?: return baseMarginPx
                if (hole.right <= width / 2) return baseMarginPx
                width - hole.left
            }

            val needed = innerEdgePx + (EXTRA_GAP_DP * res.displayMetrics.density).toInt()
            if (needed <= baseMarginPx) return baseMarginPx
            MainHook.log("$TAG: 横屏挖孔在面板一侧，marginEnd ${baseMarginPx}px -> ${needed}px")
            needed
        } catch (t: Throwable) {
            MainHook.log("$TAG: 计算挖孔避让失败，保持原边距", t)
            baseMarginPx
        }
    }
}
