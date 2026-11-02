package com.lianyu.ai.common


import android.content.Context
import android.content.Intent

import android.net.Uri
import android.os.Build
import android.provider.Settings



/**
 * 原生权限请求工具类。
 *
 * 针对 IQOO / vivo / OPPO 等国产 ROM 的权限跳转适配问题，本类提供统一的解决方案：
 * 1. 直接使用 Android 原生权限弹窗（ActivityResultContracts）请求运行时权限
 * 2. 对于无法通过运行时权限解决的设置（如自启动、后台高耗电），统一跳转到应用详情页
 * 3. 针对 OriginOS / IQOO 等 ROM，仍保留 ROM 特定的 Activity 路径跳转以提升跳转成功率
 *    （标准 Android 设置页在 OriginOS 上常被系统拦截或入口变更）。
 */
object NativePermissionRequester {


    /**
     * 打开应用详情页（通用设置页，所有 ROM 都支持）。
     * 用于引导用户手动开启自启动、后台运行等无法通过运行时权限解决的设置。
     */
    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        safeStartActivity(context, intent)
    }

    /**
     * 打开电池优化设置页。
     *
     * [FIX] 2026-06-23: 在 OriginOS/IQOO/Honor 设备上，使用专用的 OriginOSBatteryOptimizer
     * 替代标准 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS，解决点击无反应问题。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        if (RomUtils.isVivo || RomUtils.isHuawei) {
            OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)
            return
        }
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        safeStartActivity(context, intent)
    }

    /**
     * 请求忽略电池优化（直接弹系统对话框）。
     *
     * [FIX] 2026-06-22: 在 IQOO/OriginOS/Honor 上，系统可能拦截 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * 导致 Intent 无法启动。添加 try-catch 兜底，失败时跳转到专用设置页。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // OriginOS/IQOO/Honor 等 ROM 可能拦截此 Intent，兜底跳转到专用设置页
            SecureLog.w("NativePermissionRequester", "requestIgnoreBatteryOptimizations failed: ${e.message}, fallback to settings page")
            openBatteryOptimizationSettings(context)
        }
    }

    /**
     * 检查是否已忽略电池优化。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }





    private fun safeStartActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // 最终兜底：尝试打开设置主页面
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                // ignore
            }
        }
    }


}
