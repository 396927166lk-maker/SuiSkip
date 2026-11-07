package com.sui.skip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.sui.skip.core.ModeAndKeepAliveManager
import com.sui.skip.core.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SkipAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastSkippedPkg: String = ""
    private var lastSkipTimestamp: Long = 0L

    companion object {
        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var totalSkippedCount = 0
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        serviceScope.launch {
            ModeAndKeepAliveManager.enforceKeepAliveAndAccessibility(applicationContext)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return
        if (pkgName in RuleEngine.SYSTEM_WHITELIST) return

        val now = System.currentTimeMillis()
        if (pkgName == lastSkippedPkg && (now - lastSkipTimestamp) < 1500L) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val metrics = resources.displayMetrics
        val targetNode = RuleEngine.findTargetSkipNode(
            rootNode = rootNode,
            pkgName = pkgName,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )

        if (targetNode != null) {
            lastSkippedPkg = pkgName
            lastSkipTimestamp = now
            performOptimizedSkipAction(targetNode)
        }
    }

    /**
     * 触摸优化调度引擎：
     * 1. 优先尝试无感语义点击 (performAction(ACTION_CLICK))，零触摸占用；
     * 2. 当开启「触摸优化」时，由特权服务 (Shizuku / Root) 直接执行底层点击与滑动，
     *    避免无障碍 dispatchGesture 阻塞 InputDispatcher 引起的触摸卡顿；
     * 3. 仅在无特权服务时回退至无障碍手势点击/滑动。
     */
    private fun performOptimizedSkipAction(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        // 阶段 1：优先语义节点点击（不经过屏幕触摸派发，绝对不卡顿）
        var current: AccessibilityNodeInfo? = node
        var clickedByNode = false
        var depth = 0
        while (current != null && depth < 3) {
            if (current.isClickable) {
                clickedByNode = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickedByNode) break
            }
            current = current.parent
            depth++
        }

        // 阶段 2：针对自绘不可点击控件，启用「触摸优化」特权服务点击/滑动
        if (!clickedByNode && centerX > 0 && centerY > 0) {
            val touchOpt = ModeAndKeepAliveManager.isTouchOptimizationEnabled(applicationContext)
            if (touchOpt) {
                serviceScope.launch {
                    val ok = ModeAndKeepAliveManager.injectPrivilegedTap(centerX, centerY)
                    if (!ok) {
                        dispatchGestureTap(centerX.toFloat(), centerY.toFloat())
                    }
                }
            } else {
                dispatchGestureTap(centerX.toFloat(), centerY.toFloat())
            }
        }

        totalSkippedCount++
        mainHandler.post {
            Toast.makeText(applicationContext, "SuiSkip: 已极速跳过广告", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 支持滑动跳过广告（开启触摸优化时由特权服务完成滑动）
     */
    fun performOptimizedSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (ModeAndKeepAliveManager.isTouchOptimizationEnabled(applicationContext)) {
            serviceScope.launch {
                val ok = ModeAndKeepAliveManager.injectPrivilegedSwipe(x1, y1, x2, y2, 120)
                if (!ok) {
                    dispatchGestureSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
            }
        } else {
            dispatchGestureSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        }
    }

    private fun dispatchGestureTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 40L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun dispatchGestureSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 120L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        isServiceRunning = false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        serviceScope.launch {
            if (ModeAndKeepAliveManager.isAutoEnableA11y(applicationContext)) {
                ModeAndKeepAliveManager.setAccessibilityServiceEnabledBySelf(applicationContext, true)
            }
        }
        return super.onUnbind(intent)
    }
}
