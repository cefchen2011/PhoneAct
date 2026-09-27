package com.dsh.phoneact.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.dsh.phoneact.service.ActAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

data class UiNode(
    val text: String,
    val desc: String,
    val className: String,
    val packageName: String,
    val bounds: Rect2,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val depth: Int,
    val children: List<UiNode>,
) {
    val label: String get() = text.ifBlank { desc }
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("desc", desc)
        put("className", className)
        put("packageName", packageName)
        put("bounds", bounds.toJson())
        put("center", JSONArray().put(bounds.centerX).put(bounds.centerY))
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("editable", editable)
        put("scrollable", scrollable)
        put("enabled", enabled)
        put("focused", focused)
        put("depth", depth)
        put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })
    }
}

object UiTree {
    /** 抓取当前活动窗口的节点树。无无障碍服务时返回 null。 */
    fun capture(maxDepth: Int = 40): UiNode? {
        val svc = ActAccessibilityService.inst ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        return convert(root, 0, maxDepth)
    }

    private fun convert(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): UiNode {
        val r = Rect()
        node.getBoundsInScreen(r)
        val kids = if (depth >= maxDepth) emptyList() else {
            (0 until node.childCount).mapNotNull { i ->
                runCatching { node.getChild(i) }.getOrNull()?.let { convert(it, depth + 1, maxDepth) }
            }
        }
        return UiNode(
            text = node.text?.toString()?.trim().orEmpty(),
            desc = node.contentDescription?.toString()?.trim().orEmpty(),
            className = node.className?.toString().orEmpty(),
            packageName = node.packageName?.toString().orEmpty(),
            bounds = Rect2(r.left, r.top, r.right, r.bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            focused = node.isFocused,
            depth = depth,
            children = kids,
        )
    }

    fun flatten(root: UiNode?): List<UiNode> {
        if (root == null) return emptyList()
        val out = ArrayList<UiNode>(64)
        fun walk(n: UiNode) {
            out.add(n)
            n.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /** 带文字的可见节点（用于与视觉识别结果互校）。 */
    fun labeledNodes(): List<UiNode> = flatten(capture()).filter {
        it.label.isNotBlank() && it.bounds.width > 0 && it.bounds.height > 0
    }

    /** 按文本/描述查找节点并点击。 */
    fun clickByText(text: String, exact: Boolean = false): Boolean {
        val svc = ActAccessibilityService.inst ?: return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        val target = findByText(root, text, exact) ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null) {
            if (node.isClickable && node.isEnabled) {
                return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            }
            node = runCatching { node.parent }.getOrNull()
        }
        return runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
    }

    fun findByText(root: AccessibilityNodeInfo, text: String, exact: Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val hit = if (exact) t.equals(text, true) || d.equals(text, true)
            else t.contains(text, true) || d.contains(text, true)
            if (hit) return n
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 当前获得焦点的可编辑节点。 */
    fun focusedEditable(): AccessibilityNodeInfo? {
        val svc = ActAccessibilityService.inst ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            if (n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        // 退而求其次：任意可编辑节点
        queue.add(root); visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val n = queue.removeFirst()
            visited++
            if (n.isEditable && n.isEnabled) return n
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }
}
