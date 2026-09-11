package com.miui.appvolumebar.status

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.miui.appvolumebar.MainHook
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class HookTracker(private val packageName: String) {

    private val items = ConcurrentHashMap<String, HookItem>()
    private val orderedIds = CopyOnWriteArrayList<String>()
    private var contextRef: WeakReference<Context>? = null
    private var appVersionName: String? = null
    private var appVersionCode: Long = 0L
    private val isReceiverRegistered = AtomicBoolean(false)

    fun attachContext(context: Context) {
        val appCtx = context.applicationContext ?: context
        contextRef = WeakReference(appCtx)
        try {
            val pi = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
            appVersionName = pi.versionName
            appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (_: Throwable) {}

        registerQueryReceiver(appCtx)
        sync(appCtx)
    }

    private fun registerQueryReceiver(context: Context) {
        if (isReceiverRegistered.compareAndSet(false, true)) {
            try {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action == MainHook.ACTION_QUERY_HOOK_STATUS) {
                            MainHook.log("HookTracker[$packageName] received query broadcast, reporting status")
                            sync(c ?: contextRef?.get())
                        }
                    }
                }
                val filter = IntentFilter(MainHook.ACTION_QUERY_HOOK_STATUS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                MainHook.log("HookTracker[$packageName] registered query receiver successfully")
            } catch (t: Throwable) {
                MainHook.log("HookTracker[$packageName] failed to register query receiver", t)
            }
        }
    }

    fun record(
        id: String,
        name: String,
        targetClass: String,
        targetMethod: String,
        status: HookState,
        detail: String? = null
    ) {
        if (!orderedIds.contains(id)) {
            orderedIds.add(id)
        }
        val existing = items[id]
        val newItem = HookItem(
            id = id,
            name = name,
            targetClass = targetClass,
            targetMethod = targetMethod,
            status = status,
            detail = detail ?: existing?.detail,
            invokeCount = existing?.invokeCount ?: 0,
            lastInvokeTime = existing?.lastInvokeTime ?: 0L
        )
        items[id] = newItem
        sync()
    }

    fun recordSuccess(id: String, name: String, targetClass: String, targetMethod: String, detail: String? = null) {
        record(id, name, targetClass, targetMethod, HookState.SUCCESS, detail)
    }

    fun recordFailure(id: String, name: String, targetClass: String, targetMethod: String, error: Throwable) {
        val detail = error.message ?: error.javaClass.simpleName
        record(id, name, targetClass, targetMethod, HookState.FAILED, detail)
    }

    fun recordNotFound(id: String, name: String, targetClass: String, targetMethod: String, detail: String? = null) {
        record(id, name, targetClass, targetMethod, HookState.NOT_FOUND, detail ?: "未找到目标类或方法")
    }

    fun recordWaiting(id: String, name: String, targetClass: String, targetMethod: String, detail: String? = null) {
        record(id, name, targetClass, targetMethod, HookState.WAITING, detail ?: "等待加载或触发")
    }

    fun recordInvoke(id: String) {
        val existing = items[id] ?: return
        items[id] = existing.copy(
            invokeCount = existing.invokeCount + 1,
            lastInvokeTime = System.currentTimeMillis()
        )
    }

    fun buildReport(): PackageStatusReport {
        val list = orderedIds.mapNotNull { items[it] }
        return PackageStatusReport(
            packageName = packageName,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            processName = packageName,
            pid = Process.myPid(),
            reportTime = System.currentTimeMillis(),
            items = list
        )
    }

    fun sync(context: Context? = null) {
        val ctx = context ?: contextRef?.get() ?: return
        try {
            val report = buildReport()
            val jsonString = report.toJson().toString()

            // 通道 1: 主动尝试向模块的 ContentProvider 推送持久化缓存
            try {
                val uri = Uri.parse("content://${HookStatusProvider.AUTHORITY}")
                val bundle = Bundle().apply {
                    putString(HookStatusProvider.EXTRA_REPORT_JSON, jsonString)
                    putString(HookStatusProvider.EXTRA_PACKAGE_NAME, packageName)
                }
                ctx.contentResolver.call(uri, HookStatusProvider.METHOD_REPORT, packageName, bundle)
            } catch (_: Throwable) {}

            // 通道 2: 发送单播广播至模块界面
            try {
                val replyIntent = Intent(MainHook.ACTION_REPORT_HOOK_STATUS).apply {
                    setPackage(HookStatusProvider.MODULE_PACKAGE)
                    putExtra(HookStatusProvider.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(HookStatusProvider.EXTRA_REPORT_JSON, jsonString)
                }
                ctx.sendBroadcast(replyIntent)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }
}
