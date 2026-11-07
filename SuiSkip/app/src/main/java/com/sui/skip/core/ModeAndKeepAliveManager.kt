package com.sui.skip.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sui.skip.service.SkipAccessibilityService
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

enum class WorkMode(val id: Int, val title: String) {
    ACCESSIBILITY_ONLY(0, "标准无障碍模式 (免Root)"),
    SHIZUKU_ENHANCED(1, "Shizuku 增强模式 (ADB特权)"),
    ROOT_ULTIMATE(2, "ROOT 终极模式 (永生保活)")
}

object ModeAndKeepAliveManager {

    private const val PREF_NAME = "sui_skip_prefs"
    private const val KEY_MODE = "work_mode"
    private const val KEY_AUTO_ENABLE_A11Y = "auto_enable_a11y"
    private const val KEY_TOUCH_OPTIMIZATION = "touch_optimization"

    fun getCurrentMode(context: Context): WorkMode {
        val id = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_MODE, 0)
        return WorkMode.entries.find { it.id == id } ?: WorkMode.ACCESSIBILITY_ONLY
    }

    fun setCurrentMode(context: Context, mode: WorkMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, mode.id).apply()
        enforceKeepAliveAndAccessibility(context, mode)
    }

    fun isAutoEnableA11y(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENABLE_A11Y, true)
    }

    fun setAutoEnableA11y(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_ENABLE_A11Y, enabled).apply()
        if (enabled) {
            grantWriteSecureSettingsPermanent(context)
            setAccessibilityServiceEnabledBySelf(context, true)
        }
    }

    fun isTouchOptimizationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUCH_OPTIMIZATION, true)
    }

    fun setTouchOptimizationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TOUCH_OPTIMIZATION, enabled).apply()
    }

    /**
     * 检查是否已永久拥有「写入安全设置权限」(WRITE_SECURE_SETTINGS)
     * 只要通过 Shizuku / Root / ADB 授予过一次，即使重启手机、关闭 Shizuku，应用依然永久拥有此权限！
     */
    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 永久授予应用「写入安全设置权限」(pm grant com.sui.skip android.permission.WRITE_SECURE_SETTINGS)
     */
    fun grantWriteSecureSettingsPermanent(context: Context): Boolean {
        if (hasWriteSecureSettingsPermission(context)) return true
        val grantCmd = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        val granted = when {
            checkRootAvailable() -> Shell.cmd(grantCmd).exec().isSuccess
            checkShizukuAvailable() -> execShizukuCommand(grantCmd)
            else -> false
        }
        return granted || hasWriteSecureSettingsPermission(context)
    }

    /**
     * 应用自行控制开启或关闭无障碍服务（优先使用已永久授予的 WRITE_SECURE_SETTINGS 权限直接写入 Settings.Secure）
     */
    fun setAccessibilityServiceEnabledBySelf(context: Context, enable: Boolean): Boolean {
        val serviceName = ComponentName(context, SkipAccessibilityService::class.java).flattenToString()
        val currentServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val list = currentServices.split(':').filter { it.isNotBlank() }.toMutableList()
        if (enable) {
            if (list.none { it.equals(serviceName, ignoreCase = true) }) {
                list.add(serviceName)
            }
        } else {
            list.removeAll { it.equals(serviceName, ignoreCase = true) }
        }
        val updatedServices = list.joinToString(":")

        // 1. 若已拥有永久 WRITE_SECURE_SETTINGS 权限，应用进程直接自行修改系统安全设置（无需每次启动 Shizuku/Root）
        if (hasWriteSecureSettingsPermission(context)) {
            return try {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updatedServices
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    if (enable || list.isNotEmpty()) 1 else 0
                )
                true
            } catch (e: Exception) {
                false
            }
        }

        // 2. 否则尝试先通过特权服务永久授权并写入
        if (grantWriteSecureSettingsPermanent(context)) {
            return try {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updatedServices
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, SkipAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    /**
     * 核心保活与权限自愈：自动授予 WRITE_SECURE_SETTINGS + 自动开启无障碍 + ColorOS 防杀后台加固
     */
    fun enforceKeepAliveAndAccessibility(context: Context, mode: WorkMode = getCurrentMode(context)): Boolean {
        if (isAutoEnableA11y(context)) {
            grantWriteSecureSettingsPermanent(context)
            setAccessibilityServiceEnabledBySelf(context, true)
        }

        val serviceName = ComponentName(context, SkipAccessibilityService::class.java).flattenToString()
        val currentServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val existingList = currentServices.split(':').filter { it.isNotBlank() }.toMutableList()
        if (isAutoEnableA11y(context) && existingList.none { it.equals(serviceName, ignoreCase = true) }) {
            existingList.add(serviceName)
        }
        val newServices = existingList.joinToString(":")

        val enableCmds = listOf(
            "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
            "settings put secure enabled_accessibility_services '$newServices'",
            "settings put secure accessibility_enabled 1",
            "dumpsys deviceidle whitelist +${context.packageName}",
            "cmd appops set ${context.packageName} RUN_IN_BACKGROUND allow",
            "cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow"
        )

        return when (mode) {
            WorkMode.ROOT_ULTIMATE -> {
                if (checkRootAvailable()) {
                    val pid = Process.myPid()
                    val rootCmds = enableCmds.toMutableList().apply {
                        add("echo -1000 > /proc/$pid/oom_score_adj")
                    }
                    Shell.cmd(*rootCmds.toTypedArray()).exec().isSuccess
                } else isAccessibilityEnabled(context)
            }

            WorkMode.SHIZUKU_ENHANCED -> {
                if (checkShizukuAvailable()) {
                    enableCmds.all { execShizukuCommand(it) }
                } else isAccessibilityEnabled(context)
            }

            WorkMode.ACCESSIBILITY_ONLY -> {
                isAccessibilityEnabled(context)
            }
        }
    }

    fun checkRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
    }

    fun checkShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun execShizukuCommand(cmd: String): Boolean {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 触摸优化：由特权服务 (Shizuku / Root) 直接通过底层 InputManager 注入点击，
     * 避免调用 AccessibilityService.dispatchGesture 抢占系统触摸分发通道导致的划动掉帧与多指断触！
     */
    fun injectPrivilegedTap(x: Int, y: Int): Boolean {
        val tapCmd = "input tap $x $y"
        return when {
            checkRootAvailable() -> Shell.cmd(tapCmd).exec().isSuccess
            checkShizukuAvailable() -> execShizukuCommand(tapCmd)
            else -> false
        }
    }

    /**
     * 触摸优化：由特权服务 (Shizuku / Root) 完成滑动操作（用于翻页式/滑动跳过开屏广告），
     * 彻底消除无障碍手势引起的屏幕触摸卡顿。
     */
    fun injectPrivilegedSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 120): Boolean {
        val swipeCmd = "input swipe $x1 $y1 $x2 $y2 $durationMs"
        return when {
            checkRootAvailable() -> Shell.cmd(swipeCmd).exec().isSuccess
            checkShizukuAvailable() -> execShizukuCommand(swipeCmd)
            else -> false
        }
    }
}
