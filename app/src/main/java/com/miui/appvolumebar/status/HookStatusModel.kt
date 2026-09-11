package com.miui.appvolumebar.status

import org.json.JSONArray
import org.json.JSONObject

enum class HookState {
    SUCCESS,    // Hook 成功
    FAILED,     // 找到方法但 Hook 抛出异常
    NOT_FOUND,  // 未找到目标类或方法（版本变更）
    WAITING     // 等待条件触发（如插件尚未加载）
}

data class HookItem(
    val id: String,
    val name: String,
    val targetClass: String,
    val targetMethod: String,
    val status: HookState,
    val detail: String? = null,
    val invokeCount: Int = 0,
    val lastInvokeTime: Long = 0L
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("targetClass", targetClass)
            put("targetMethod", targetMethod)
            put("status", status.name)
            put("detail", detail ?: "")
            put("invokeCount", invokeCount)
            put("lastInvokeTime", lastInvokeTime)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): HookItem {
            return HookItem(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                targetClass = json.optString("targetClass", ""),
                targetMethod = json.optString("targetMethod", ""),
                status = try {
                    HookState.valueOf(json.optString("status", HookState.WAITING.name))
                } catch (_: Throwable) {
                    HookState.WAITING
                },
                detail = json.optString("detail").takeIf { it.isNotEmpty() },
                invokeCount = json.optInt("invokeCount", 0),
                lastInvokeTime = json.optLong("lastInvokeTime", 0L)
            )
        }
    }
}

data class PackageStatusReport(
    val packageName: String,
    val appVersionName: String? = null,
    val appVersionCode: Long = 0L,
    val processName: String? = null,
    val pid: Int = 0,
    val reportTime: Long = System.currentTimeMillis(),
    val items: List<HookItem> = emptyList()
) {
    fun toJson(): JSONObject {
        val array = JSONArray()
        for (item in items) {
            array.put(item.toJson())
        }
        return JSONObject().apply {
            put("packageName", packageName)
            put("appVersionName", appVersionName ?: "")
            put("appVersionCode", appVersionCode)
            put("processName", processName ?: "")
            put("pid", pid)
            put("reportTime", reportTime)
            put("items", array)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PackageStatusReport {
            val itemList = mutableListOf<HookItem>()
            val array = json.optJSONArray("items")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    itemList.add(HookItem.fromJson(obj))
                }
            }
            return PackageStatusReport(
                packageName = json.optString("packageName", ""),
                appVersionName = json.optString("appVersionName").takeIf { it.isNotEmpty() },
                appVersionCode = json.optLong("appVersionCode", 0L),
                processName = json.optString("processName").takeIf { it.isNotEmpty() },
                pid = json.optInt("pid", 0),
                reportTime = json.optLong("reportTime", 0L),
                items = itemList
            )
        }
    }
}
