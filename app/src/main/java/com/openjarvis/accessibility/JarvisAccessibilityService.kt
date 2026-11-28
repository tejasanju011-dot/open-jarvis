package com.openjarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import kotlinx.coroutines.delay

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun openAppByPackage(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (error: Exception) {
            android.util.Log.e("OpenJarvis", "Could not launch package: $packageName", error)
            false
        }
    }

    suspend fun waitForAppForeground(packageName: String, timeoutMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val rootNode = rootInActiveWindow
            val foregroundPackage = rootNode?.packageName?.toString()
            rootNode?.recycle()
            if (foregroundPackage == packageName) return true
            delay(250)
        }
        return false
    }

    fun openAppByLabel(label: String): Boolean {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val normalizedLabel = label.lowercase()
        for (app in pm.queryIntentActivities(intent, 0)) {
            val appLabel = app.loadLabel(pm).toString().lowercase()
            if (appLabel.contains(normalizedLabel) || normalizedLabel.contains(appLabel)) {
                return openAppByPackage(app.activityInfo.packageName)
            }
        }
        return false
    }

    /**
     * Finds a matching accessibility node across the active and interactive windows.
     * Clicks the nearest clickable ancestor when the matched text itself is not clickable.
     * Uses a bounds-based gesture only as a last resort for visible, non-empty nodes.
     */
    fun tapByText(text: String): Boolean {
        if (text.isBlank()) return false
        val roots = obtainWindowRoots()
        try {
            for (root in roots) {
                val match = findBestMatchingNode(root, text) ?: continue
                try {
                    if (clickNodeOrAncestor(match)) return true
                    if (tapNodeBounds(match)) return true
                } finally {
                    match.recycle()
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("OpenJarvis", "Accessibility tap failed for '$text'", error)
        } finally {
            roots.forEach { it.recycle() }
        }
        android.util.Log.w("OpenJarvis", "No tappable accessibility match for: $text")
        return false
    }

    private fun obtainWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.forEach { window ->
                try {
                    window.root?.let { roots.add(it) }
                } catch (error: Exception) {
                    android.util.Log.w("OpenJarvis", "Could not read accessibility window", error)
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("OpenJarvis", "Could not enumerate accessibility windows", error)
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }
        return roots
    }

    private fun findBestMatchingNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val targetNorm = normalizeForMatch(target)
        val targetTokens = targetNorm.split(' ').filter { it.length > 1 }.toSet()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                val candidates = listOfNotNull(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.hintText?.toString(),
                    node.viewIdResourceName?.substringAfterLast('/')
                )
                for (candidate in candidates) {
                    val candidateNorm = normalizeForMatch(candidate)
                    if (candidateNorm.isBlank()) continue
                    val score = when {
                        candidateNorm == targetNorm -> 1.0
                        candidateNorm.contains(targetNorm) || targetNorm.contains(candidateNorm) -> 0.92
                        else -> {
                            val candidateTokens = candidateNorm.split(' ').filter { it.length > 1 }.toSet()
                            if (targetTokens.isEmpty() || candidateTokens.isEmpty()) 0.0
                            else targetTokens.intersect(candidateTokens).size.toDouble() /
                                targetTokens.size.toDouble()
                        }
                    }
                    if (score > bestScore && score >= 0.60 && node.isVisibleToUser && node.isEnabled) {
                        best?.recycle()
                        best = AccessibilityNodeInfo.obtain(node)
                        bestScore = score
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } finally {
                node.recycle()
            }
        }
        return best
    }

    private fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun clickNodeOrAncestor(matched: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(matched)
        try {
            repeat(7) {
                val node = current ?: return false
                if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
                val parent = node.parent
                node.recycle()
                current = parent
            }
            return matched.isEnabled &&
                matched.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            current?.recycle()
        }
    }

    private fun tapNodeBounds(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !node.isVisibleToUser) return false
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val roots = obtainWindowRoots()
        try {
            for (root in roots) {
                val editable = findEditableNode(root) ?: continue
                try {
                    editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                        android.util.Log.d("OpenJarvis", "Text entered using accessibility set-text")
                        return true
                    }
                } finally {
                    editable.recycle()
                }
            }
            android.util.Log.w("OpenJarvis", "No editable accessibility field accepted text")
            return false
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isEnabled && node.isVisibleToUser) {
                val result = AccessibilityNodeInfo.obtain(node)
                node.recycle()
                queue.forEach { it.recycle() }
                return result
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return null
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
