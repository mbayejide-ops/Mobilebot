package com.aiagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AgentService : AccessibilityService() {

    companion object {
        var instance: AgentService? = null

        val APPS = mapOf(
            "youtube" to "com.google.android.youtube",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "whatsapp" to "com.whatsapp"
        )

        const val MAX_STEPS = 20

        val SYSTEM_PROMPT = """
You control an Android phone through an accessibility service on behalf of the user.
The user's command may be in Bengali, Banglish or English.
Every turn you get the command, the steps already done, and the current screen as numbered elements like [3] button "Search".
Reply with exactly ONE JSON object and nothing else (no markdown). Allowed actions:
{"action":"open_app","app":"youtube|facebook|messenger|whatsapp"}
{"action":"youtube_search","query":"..."}  (opens YouTube search results directly)
{"action":"click","index":N}  (works on any listed element, including text)
{"action":"type","index":N,"text":"..."}  (N must be an input element)
{"action":"swipe","direction":"up|down","count":1,"delay_sec":3}  (direction up = finger moves up = next video or further down the feed; use count and delay_sec for repeated scrolling, for example Facebook Reels)
{"action":"back"}
{"action":"wait"}
{"action":"done","message":"short result in the user's language"}
Rules:
- One action per reply. Only use indexes that exist on the current screen.
- If the needed app is not in the foreground, open it first.
- To play a song or video: use youtube_search, then click the first video result (an element whose label contains "play video").
- To message someone: open the app, use its search to find the contact, open the chat, type the message in the message box, then click Send. The system itself asks the user to confirm before any Send click, so never skip the Send click and never ask in text.
- If the screen does not show what you need, try swipe, back or wait. If you are stuck or it is impossible, use done and explain.
- Use done as soon as the task is complete.
""".trimIndent()
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var stopRequested = false

    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private var lastTyped = ""

    // ---------- Overlay (Stop / Haan / Na) ----------

    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var btnYes: Button? = null
    private var btnNo: Button? = null
    private val answers = LinkedBlockingQueue<Boolean>()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopRequested = true
        hidePanel()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun ensurePanel() {
        if (panel != null) return
        val d = resources.displayMetrics.density
        val p = (8 * d).toInt()

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = (240 * d).toInt()
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopRequested = true
                answers.offer(false)
            }
        }
        val yes = Button(this).apply {
            text = "Haan, pathao"
            visibility = View.GONE
            setOnClickListener { answers.offer(true) }
        }
        val no = Button(this).apply {
            text = "Na"
            visibility = View.GONE
            setOnClickListener { answers.offer(false) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 20, 20, 20))
            setPadding(p, p, p, p)
            addView(status)
            addView(yes)
            addView(no)
            addView(stop)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.y = (120 * d).toInt()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm?.addView(box, lp)
        panel = box
        statusView = status
        btnYes = yes
        btnNo = no
    }

    private fun setStatus(msg: String) {
        handler.post {
            ensurePanel()
            statusView?.text = msg
        }
    }

    private fun hidePanel() {
        handler.post {
            panel?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
            panel = null
        }
    }

    private fun confirm(question: String): Boolean {
        answers.clear()
        handler.post {
            ensurePanel()
            statusView?.text = question
            btnYes?.visibility = View.VISIBLE
            btnNo?.visibility = View.VISIBLE
        }
        val r = answers.poll(60, TimeUnit.SECONDS) ?: false
        handler.post {
            btnYes?.visibility = View.GONE
            btnNo?.visibility = View.GONE
        }
        return r && !stopRequested
    }

    fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------- Agent loop ----------

    fun runAgent(command: String, baseUrl: String, key: String, model: String) {
        if (running) {
            toast("Agent age thekei cholche")
            return
        }
        running = true
        stopRequested = false
        Thread {
            val history = ArrayList<String>()
            try {
                for (step in 1..MAX_STEPS) {
                    if (stopRequested) {
                        setStatus("Stop kora hoyeche")
                        Thread.sleep(1500)
                        break
                    }
                    setStatus("Step " + step + ": AI bhabche...")
                    val screen = describeScreen()
                    val user = buildString {
                        append("Command: ").append(command).append("\n\n")
                        append("Previous steps:\n")
                        if (history.isEmpty()) {
                            append("(none)\n")
                        } else {
                            history.takeLast(8).forEach { append(it).append("\n") }
                        }
                        append("\nCurrent screen:\n").append(screen)
                    }
                    val reply = Llm.chat(baseUrl, key, model, SYSTEM_PROMPT, user)
                    val act = Llm.extractJson(reply)
                    if (act.optString("action") == "done") {
                        setStatus(act.optString("message", "Shesh"))
                        Thread.sleep(3000)
                        break
                    }
                    setStatus("Step " + step + ": " + act.optString("action"))
                    val result = execute(act)
                    history.add(step.toString() + ". " + act.toString() + " -> " + result)
                    Thread.sleep(2000)
                }
            } catch (e: Exception) {
                setStatus("Error: " + e.message)
                try { Thread.sleep(5000) } catch (x: Exception) {}
            } finally {
                running = false
                hidePanel()
            }
        }.start()
    }

    private fun execute(a: JSONObject): String {
        return when (a.optString("action")) {
            "open_app" -> {
                val n = a.optString("app").lowercase()
                val pkg = APPS[n] ?: n
                if (openApp(pkg)) "ok" else "app khuje pai nai"
            }
            "youtube_search" -> youtubeSearch(a.optString("query"))
            "click" -> doClick(a.optInt("index", -1))
            "type" -> doType(a.optInt("index", -1), a.optString("text"))
            "swipe" -> doSwipe(a)
            "back" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                "ok"
            }
            "wait" -> {
                Thread.sleep(2000)
                "ok"
            }
            else -> "unknown action"
        }
    }

    // ---------- Actions ----------

    private fun openApp(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    private fun youtubeSearch(query: String): String {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val i = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.youtube")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            "ok"
        } catch (e: Exception) {
            "YouTube app nai"
        }
    }

    private fun isSend(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("send") || l.contains("পাঠা")
    }

    private fun doClick(index: Int): String {
        val node = nodes.getOrNull(index) ?: return "invalid index"
        if (isSend(labelOf(node))) {
            val ok = confirm("Message pathabo?\n\"" + lastTyped + "\"")
            if (!ok) {
                stopRequested = true
                return "user pathate dilo na"
            }
        }
        return if (clickNode(node)) "ok" else "click hoy nai"
    }

    private fun doType(index: Int, text: String): String {
        val node = nodes.getOrNull(index) ?: return "invalid index"
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) lastTyped = text
        return if (ok) "ok" else "type hoy nai"
    }

    private fun doSwipe(a: JSONObject): String {
        val dir = a.optString("direction", "up")
        val count = a.optInt("count", 1).coerceIn(1, 50)
        val delay = a.optDouble("delay_sec", 2.0).coerceIn(0.5, 60.0)
        for (i in 1..count) {
            if (stopRequested) break
            swipe(dir)
            setStatus("Swipe " + i + "/" + count)
            Thread.sleep((delay * 1000).toLong())
        }
        return "ok"
    }

    private fun swipe(dir: String): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val high = m.heightPixels * 0.25f
        val low = m.heightPixels * 0.75f
        val path = Path()
        if (dir == "up") {
            path.moveTo(x, low)
            path.lineTo(x, high)
        } else {
            path.moveTo(x, high)
            path.lineTo(x, low)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(g, null, null)
    }

    // ---------- Screen reading ----------

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val c = listOf(n.text, n.contentDescription, n.hintText).firstOrNull { !it.isNullOrBlank() }
        return c?.toString() ?: ""
    }

    private fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "(screen unavailable)"
        val list = ArrayList<AccessibilityNodeInfo>()
        collect(root, list)
        nodes = list
        val sb = StringBuilder()
        sb.append("App: ").append(root.packageName).append("\n")
        if (list.isEmpty()) sb.append("(no readable elements)\n")
        list.forEachIndexed { i, n ->
            val label = labelOf(n).replace("\n", " ").take(70)
            val kind = when {
                n.isEditable -> "input"
                n.isClickable -> "button"
                n.isScrollable -> "scroll"
                else -> "text"
            }
            sb.append("[").append(i).append("] ").append(kind).append(" \"").append(label).append("\"\n")
        }
        return sb.toString()
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (out.size >= 70) return
        if (n.packageName?.toString() == packageName) return
        if (n.isVisibleToUser) {
            if (labelOf(n).isNotEmpty() || n.isEditable || n.isScrollable) out.add(n)
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out)
        }
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
