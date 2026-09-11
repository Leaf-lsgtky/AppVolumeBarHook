package com.miui.appvolumebar.status

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class HookStatusProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.miui.appvolumebar.hookstatus"
        const val MODULE_PACKAGE = "com.miui.appvolumebar"
        const val METHOD_REPORT = "report"
        const val METHOD_GET = "get"
        const val EXTRA_REPORT_JSON = "extra_report_json"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        private const val PREFS_NAME = "hook_status_cache"
        private const val KEY_PREFIX = "report_"

        private val listeners = CopyOnWriteArrayList<(PackageStatusReport) -> Unit>()

        fun addListener(listener: (PackageStatusReport) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (PackageStatusReport) -> Unit) {
            listeners.remove(listener)
        }

        fun saveReport(context: Context, report: PackageStatusReport) {
            try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit()
                    .putString(KEY_PREFIX + report.packageName, report.toJson().toString())
                    .apply()
                listeners.forEach { it(report) }
            } catch (_: Throwable) {}
        }

        fun getReport(context: Context, packageName: String): PackageStatusReport? {
            return try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonStr = sp.getString(KEY_PREFIX + packageName, null) ?: return null
                PackageStatusReport.fromJson(JSONObject(jsonStr))
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        when (method) {
            METHOD_REPORT -> {
                val jsonStr = extras?.getString(EXTRA_REPORT_JSON) ?: return null
                try {
                    val report = PackageStatusReport.fromJson(JSONObject(jsonStr))
                    saveReport(ctx, report)
                } catch (_: Throwable) {}
                return Bundle().apply { putBoolean("success", true) }
            }
            METHOD_GET -> {
                val pkg = arg ?: return null
                val report = getReport(ctx, pkg) ?: return null
                return Bundle().apply {
                    putString(EXTRA_REPORT_JSON, report.toJson().toString())
                }
            }
        }
        return null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
