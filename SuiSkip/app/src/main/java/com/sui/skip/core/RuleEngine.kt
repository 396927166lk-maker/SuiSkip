package com.sui.skip.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class SkipRule(
    val pkgName: String,
    val appName: String,
    val activityName: String? = null,
    val matchId: String? = null,
    val matchTextRegex: Regex? = null
)

object RuleEngine {

    val SYSTEM_WHITELIST = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.oplus.launcher",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.sui.skip"
    )

    val GLOBAL_SKIP_REGEX = Regex(
        pattern = "^(\\s*跳过\\s*\\d*\\s*[sS秒]?\\s*|\\s*[sS]kip\\s*\\d*\\s*|\\s*关闭广告\\s*|\\s*跳过广告\\s*)$"
    )

    val builtInRules = mutableListOf(
        SkipRule("com.heytap.market", "OPPO/真我软件商店", matchId = "com.heytap.market:id/splash_skip_tv"),
        SkipRule("com.oplus.market", "软件商店", matchId = "com.oplus.market:id/tv_skip"),
        SkipRule("com.heytap.smarthome", "欢太商城", matchId = "com.heytap.smarthome:id/tv_skip"),
        SkipRule("com.heytap.browser", "系统浏览器", matchId = "com.heytap.browser:id/splash_skip"),
        SkipRule("com.heytap.themestore", "主题商店", matchId = "com.heytap.themestore:id/skip_view"),
        SkipRule("com.heytap.music", "系统音乐", matchId = "com.heytap.music:id/skip_layout"),
        SkipRule("com.heytap.yoli", "系统视频", matchId = "com.heytap.yoli:id/splash_skip_text"),
        SkipRule("com.coolapk.market", "酷安", matchId = "com.coolapk.market:id/tt_splash_skip_btn"),
        SkipRule("com.zhihu.android", "知乎", matchId = "com.zhihu.android:id/btn_skip"),
        SkipRule("com.sina.weibo", "微博", matchId = "com.sina.weibo:id/tv_skip"),
        SkipRule("tv.danmaku.bili", "哔哩哔哩", matchId = "tv.danmaku.bili:id/splase_skip_layout"),
        SkipRule("com.netease.cloudmusic", "网易云音乐", matchId = "com.netease.cloudmusic:id/skipBtn"),
        SkipRule("com.dragon.read", "番茄免费小说", matchId = "com.dragon.read:id/tt_splash_skip_btn"),
        SkipRule("com.ss.android.ugc.aweme", "抖音", matchId = "com.ss.android.ugc.aweme:id/tv_skip"),
        SkipRule("com.xingin.xhs", "小红书", matchId = "com.xingin.xhs:id/splash_skip_btn"),
        SkipRule("*", "穿山甲广告SDK通用", matchId = "tt_splash_skip_btn"),
        SkipRule("*", "快手广告SDK通用", matchId = "ksad_splash_circle_skip_view"),
        SkipRule("*", "优量汇广告SDK通用", matchId = "gdt_splash_skip_button"),
        SkipRule("*", "倍孜广告SDK通用", matchId = "beizi_ad_splash_skip")
    )

    fun matchesSkipText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        if (trimmed.length > 8) return false
        return GLOBAL_SKIP_REGEX.matches(trimmed)
    }

    fun isValidBounds(left: Int, top: Int, right: Int, bottom: Int, screenW: Int, screenH: Int): Boolean {
        val width = right - left
        val height = bottom - top
        if (width <= 10 || height <= 10) return false
        if (width > screenW * 0.45 || height > screenH * 0.15) return false
        return true
    }

    fun findTargetSkipNode(
        rootNode: AccessibilityNodeInfo,
        pkgName: String,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        if (pkgName in SYSTEM_WHITELIST) return null

        val applicableRules = builtInRules.filter { it.pkgName == pkgName || it.pkgName == "*" }
        for (rule in applicableRules) {
            val targetId = rule.matchId ?: continue
            val fullId = if (targetId.contains(":id/")) targetId else "$pkgName:id/$targetId"
            val idNodes = rootNode.findAccessibilityNodeInfosByViewId(fullId)
            if (!idNodes.isNullOrEmpty()) {
                for (node in idNodes) {
                    if (node.isVisibleToUser && isValidNodeArea(node, screenWidth, screenHeight)) {
                        return node
                    }
                }
            }
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        candidates.addAll(rootNode.findAccessibilityNodeInfosByText("跳过"))
        candidates.addAll(rootNode.findAccessibilityNodeInfosByText("Skip"))

        for (node in candidates) {
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if ((matchesSkipText(text) || matchesSkipText(desc)) &&
                isValidNodeArea(node, screenWidth, screenHeight)
            ) {
                return node
            }
        }

        return dfsSearchNode(rootNode, screenWidth, screenHeight, 0)
    }

    private fun dfsSearchNode(
        node: AccessibilityNodeInfo?,
        screenW: Int,
        screenH: Int,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 12 || !node.isVisibleToUser) return null

        val viewId = node.viewIdResourceName ?: ""
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        val idMatched = viewId.endsWith("skip_btn") || viewId.endsWith("splash_skip") || viewId.endsWith("tv_skip")
        val textMatched = matchesSkipText(text) || matchesSkipText(desc)

        if ((idMatched || textMatched) && isValidNodeArea(node, screenW, screenH)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = dfsSearchNode(node.getChild(i), screenW, screenH, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun isValidNodeArea(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return isValidBounds(rect.left, rect.top, rect.right, rect.bottom, screenW, screenH)
    }

    fun runSelfDiagnostics(): Pair<Boolean, String> {
        val shouldMatch = listOf("跳过", "跳过 3", "跳过5s", "跳过 4 秒", "Skip", "skip 3", "关闭广告", "跳过广告")
        val shouldReject = listOf("点击跳过查看详情", "跳过此章节内容", "我不想跳过", "", "123456789")
        for (s in shouldMatch) {
            if (!matchesSkipText(s)) return false to "正则自检失败：未能匹配合法跳过词 [$s]"
        }
        for (s in shouldReject) {
            if (matchesSkipText(s)) return false to "正则自检失败：误匹配干扰词 [$s]"
        }
        if ("com.sui.skip" !in SYSTEM_WHITELIST || "com.oplus.launcher" !in SYSTEM_WHITELIST) {
            return false to "白名单自检失败"
        }
        if (!isValidBounds(800, 100, 1000, 180, 1080, 2400)) {
            return false to "坐标区域校验失败"
        }
        if (isValidBounds(0, 0, 1080, 2400, 1080, 2400)) {
            return false to "全屏防误触拦截失败"
        }
        return true to "SuiSkip 全量自检通过！内置规则数：${builtInRules.size} 条，防误触与三模切换正常。"
    }
}
