package com.sui.skip.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sui.skip.R
import com.sui.skip.core.ModeAndKeepAliveManager
import com.sui.skip.core.RuleEngine
import com.sui.skip.core.WorkMode
import com.sui.skip.service.SkipAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var tvSkipCounter: TextView
    private lateinit var rgModes: RadioGroup
    private lateinit var switchAutoA11y: Switch
    private lateinit var switchTouchOpt: Switch

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            activateMode(WorkMode.SHIZUKU_ENHANCED)
        } else {
            Toast.makeText(this, "Shizuku 授权被拒绝，已回退至标准无障碍模式", Toast.LENGTH_SHORT).show()
            rgModes.check(R.id.rb_mode_accessibility)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        tvStatus = findViewById(R.id.tv_service_status)
        tvPermStatus = findViewById(R.id.tv_perm_status)
        tvSkipCounter = findViewById(R.id.tv_skip_counter)
        rgModes = findViewById(R.id.rg_work_modes)
        switchAutoA11y = findViewById(R.id.switch_auto_enable_a11y)
        switchTouchOpt = findViewById(R.id.switch_touch_optimization)

        switchAutoA11y.isChecked = ModeAndKeepAliveManager.isAutoEnableA11y(this)
        switchTouchOpt.isChecked = ModeAndKeepAliveManager.isTouchOptimizationEnabled(this)

        // 1. 监听「无障碍 自动开启（永久授予写入安全设置权限）」开关
        switchAutoA11y.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                val granted = withContext(Dispatchers.IO) {
                    ModeAndKeepAliveManager.setAutoEnableA11y(this@MainActivity, isChecked)
                    ModeAndKeepAliveManager.hasWriteSecureSettingsPermission(this@MainActivity)
                }
                refreshStatusUI()
                if (isChecked) {
                    if (granted) {
                        Toast.makeText(this@MainActivity, "已永久授予「写入安全设置权限」，无障碍已自动开启！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "请先激活一次 Shizuku 或 Root，即可永久授予写入安全设置权限", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // 2. 监听「触摸优化（由特权服务完成点击和滑动）」开关
        switchTouchOpt.setOnCheckedChangeListener { _, isChecked ->
            ModeAndKeepAliveManager.setTouchOptimizationEnabled(this, isChecked)
            val msg = if (isChecked) {
                "触摸优化已开启：由特权服务接管点击与滑动，消除无障碍触摸卡顿"
            } else {
                "触摸优化已关闭：使用标准无障碍手势"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        when (ModeAndKeepAliveManager.getCurrentMode(this)) {
            WorkMode.ACCESSIBILITY_ONLY -> rgModes.check(R.id.rb_mode_accessibility)
            WorkMode.SHIZUKU_ENHANCED -> rgModes.check(R.id.rb_mode_shizuku)
            WorkMode.ROOT_ULTIMATE -> rgModes.check(R.id.rb_mode_root)
        }

        rgModes.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_mode_accessibility -> activateMode(WorkMode.ACCESSIBILITY_ONLY)
                R.id.rb_mode_shizuku -> requestAndActivateShizuku()
                R.id.rb_mode_root -> requestAndActivateRoot()
            }
        }

        // 应用内自行控制开关无障碍按钮
        findViewById<Button>(R.id.btn_toggle_self_a11y).setOnClickListener {
            lifecycleScope.launch {
                val currentlyEnabled = ModeAndKeepAliveManager.isAccessibilityEnabled(this@MainActivity)
                val targetState = !currentlyEnabled
                val ok = withContext(Dispatchers.IO) {
                    ModeAndKeepAliveManager.setAccessibilityServiceEnabledBySelf(this@MainActivity, targetState)
                }
                refreshStatusUI()
                if (ok) {
                    val stateStr = if (targetState) "开启" else "关闭"
                    Toast.makeText(this@MainActivity, "应用已自行控制$stateStr 无障碍服务！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "尚未获得「写入安全设置权限」，请先用 Shizuku/Root 激活一次永久授权", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btn_open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_oplus_battery).setOnClickListener {
            openOplusBatteryOptimization()
        }

        findViewById<Button>(R.id.btn_self_test).setOnClickListener {
            val (ok, msg) = RuleEngine.runSelfDiagnostics()
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestAndActivateShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "未检测到 Shizuku 正在运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
                rgModes.check(R.id.rb_mode_accessibility)
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                activateMode(WorkMode.SHIZUKU_ENHANCED)
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            rgModes.check(R.id.rb_mode_accessibility)
        }
    }

    private fun requestAndActivateRoot() {
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { ModeAndKeepAliveManager.checkRootAvailable() }
            if (hasRoot) {
                activateMode(WorkMode.ROOT_ULTIMATE)
            } else {
                Toast.makeText(this@MainActivity, "未获取到 ROOT 权限，已切换回标准无障碍模式", Toast.LENGTH_LONG).show()
                rgModes.check(R.id.rb_mode_accessibility)
            }
        }
    }

    private fun activateMode(mode: WorkMode) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ModeAndKeepAliveManager.setCurrentMode(this@MainActivity, mode)
                ModeAndKeepAliveManager.enforceKeepAliveAndAccessibility(this@MainActivity, mode)
            }
            refreshStatusUI()
            if (mode != WorkMode.ACCESSIBILITY_ONLY && success) {
                Toast.makeText(this@MainActivity, "${mode.title} 已激活，已永久授予写入安全设置权限并开启触摸优化！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOplusBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            return
        }
        try {
            val oplusIntent = Intent().apply {
                component = ComponentName(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"
                )
            }
            startActivity(oplusIntent)
        } catch (e: Exception) {
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(settingsIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatusUI()
    }

    private fun refreshStatusUI() {
        val enabled = ModeAndKeepAliveManager.isAccessibilityEnabled(this)
        val hasSecurePerm = ModeAndKeepAliveManager.hasWriteSecureSettingsPermission(this)
        val mode = ModeAndKeepAliveManager.getCurrentMode(this)
        tvStatus.text = if (enabled) {
            "运行状态：正在守护中 (${mode.title})"
        } else {
            "运行状态：未开启（可点下方一键自控开启或切换 Shizuku/Root）"
        }
        tvPermStatus.text = if (hasSecurePerm) {
            "写入安全设置权限：已永久授予（应用可随时自行控制开关无障碍）"
        } else {
            "写入安全设置权限：未授予（激活一次 Shizuku/Root 或 ADB 即可永久生效）"
        }
        tvSkipCounter.text = "累计已跳过广告：${SkipAccessibilityService.totalSkippedCount} 次"
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}
