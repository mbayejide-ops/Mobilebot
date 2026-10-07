package com.aiagent.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AgentService : AccessibilityService() {

    companion object {
        var instance: AgentService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ---------- Basic tools (porer step gulote AI eigulo ke call korbe) ----------

    fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    fun openApp(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findFirst(root) {
            it.text?.toString()?.contains(text, true) == true ||
                it.contentDescription?.toString()?.contains(text, true) == true
        } ?: return false
        return clickNode(node)
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findFirst(root) { it.isScrollable } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    // ---------- Step 1 test: YouTube e search kore prothom video play ----------

    fun playOnYoutube(query: String) {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val i = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.youtube")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("YouTube app khuje pai nai")
            return
        }
        handler.postDelayed({ tryClickFirstVideo(0) }, 3500)
    }

    private fun tryClickFirstVideo(attempt: Int) {
        val root = rootInActiveWindow
        val node = root?.let {
            findFirst(it) { n ->
                n.contentDescription?.toString()?.contains("play video", true) == true
            }
        }
        if (node != null && clickNode(node)) {
            toast("Video play hocche")
            return
        }
        if (attempt < 4) {
            handler.postDelayed({ tryClickFirstVideo(attempt + 1) }, 1500)
        } else {
            toast("Video button khuje pai nai")
        }
    }

    // ---------- Helpers ----------

    private fun findFirst(
        node: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirst(child, match)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = start
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }
}
