package com.lazydoglab.zisee.screen

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.lazydoglab.zisee.MainActivity
import com.lazydoglab.zisee.ar.annotation.VideoPoint

/** Three independent windows: passive drawing, bounded controls, temporary drawing input. */
class GuidanceOverlay(private val context: Context,
    private val put: (GuidanceInput) -> Unit,
    private val command: (GuidanceOp, GuidanceMark?) -> Unit,
    private val pause: () -> Unit, private val stop: () -> Unit, private val failure: () -> Unit,
    private val clearHighlight: () -> Unit = {}) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val annotation = GuidanceCanvas(context)
    private var input: GuidanceCanvas? = null
    private val menu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xee12181d.toInt()) }
    private val panel = ScrollView(context).apply { addView(menu) }
    private var state = GuidanceState()
    private var expanded = false
    private var hidden = false
    private var closed = false
    private val controls = params(dp(56), WindowManager.LayoutParams.WRAP_CONTENT).apply { x = 0; y = dp(100) }
    private val collapse = Runnable { if (input == null) { expanded = false; rebuild() } }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun params(w: Int, h: Int, passive: Boolean = false) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (passive) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (passive) alpha = if (Build.VERSION.SDK_INT >= 31)
            minOf(.75f, context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch) else .75f
    }
    init {
        try {
            wm.addView(annotation, params(-1, -1, true)); wm.addView(panel, controls); rebuild()
        } catch (e: RuntimeException) { close(); throw e }
    }
    fun update(value: GuidanceState) {
        if (closed) return
        val changed = value.paused != state.paused || value.connected != state.connected || value.geometry != state.geometry
        if (value.geometry != state.geometry || value.paused || !value.connected) finishDrawing()
        state = value; annotation.state = value; input?.state = value
        if (changed) rebuild()
    }
    // Overlay uses application context and platform theme, not an AppCompat Activity theme.
    @android.annotation.SuppressLint("AppCompatCustomView")
    private class MenuButton(context: Context) : Button(context) {
        override fun performClick(): Boolean = super.performClick()
    }
    private fun button(text: String, action: () -> Unit): MenuButton = MenuButton(context).apply {
        this.text = text; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; setPadding(dp(4), dp(4), dp(4), dp(4))
        setTextColor(0xffeeeeee.toInt()); minimumHeight = dp(48)
        setOnClickListener { handler.removeCallbacks(collapse); action(); if (input == null) handler.postDelayed(collapse, 3_000) }
        menu.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun rebuild() {
        if (closed) return
        menu.removeAllViews(); panel.scrollTo(0, 0); controls.width = dp(if (expanded) 170 else 56)
        controls.height = if (expanded) minOf(dp(440), (context.resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(56))) else dp(56)
        val bubble = button(if (input != null) "完成标注 ✓" else if (state.paused) "Ⅱ" else if (!state.connected) "!" else "共享") {
            if (input != null) finishDrawing() else expanded = !expanded
            rebuild()
        }
        var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0; var moved = false
        bubble.contentDescription = if (input != null) "完成标注，恢复操作手机" else "屏幕共享控制菜单，可拖动"
        bubble.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.parent.requestDisallowInterceptTouchEvent(true)
                    startX = e.rawX; startY = e.rawY; originalX = controls.x; originalY = controls.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX-startX) + kotlin.math.abs(e.rawY-startY) > dp(8)) moved = true
                    if (moved) { controls.x = originalX + (e.rawX-startX).toInt(); controls.y = originalY + (e.rawY-startY).toInt(); position() }; true
                }
                MotionEvent.ACTION_UP -> { if (moved) { val width = context.resources.displayMetrics.widthPixels
                    controls.x = if (controls.x < width/2) 0 else width-controls.width; position() } else view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { moved = false; view.parent.requestDisallowInterceptTouchEvent(false); true }
                else -> false
            }
        }
        if (expanded) {
            if (!state.semanticAvailable) button("开启 UI 元素高亮") {
                try { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: android.content.ActivityNotFoundException) { failure() }
            }
            if (input != null) button("正在标注 · 完成后可操作手机") { finishDrawing(); rebuild() }
            else button("画笔") { if (!state.paused && state.connected) beginDrawing() }
            button("撤销我的标注") { command(GuidanceOp.UNDO, null) }
            button("清除…") {
                menu.removeAllViews()
                button("清除我的标注") { command(GuidanceOp.CLEAR_OWN, null); rebuild() }
                button("清除全部标注") { command(GuidanceOp.CLEAR_ALL, null); rebuild() }
                button("取消") { rebuild() }
            }
            button(if (hidden) "显示标注" else "隐藏标注") { hidden = !hidden; if (hidden) clearHighlight(); annotation.visibility = if (hidden) View.INVISIBLE else View.VISIBLE; rebuild() }
            button(if (state.paused) "恢复共享" else "暂停共享") { pause() }
            button("停止共享…") {
                menu.removeAllViews(); button("停止共享（通话继续）") { stop() }; button("取消") { rebuild() }
            }
            button("返回咫尺") {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        position()
    }
    private fun position() {
        val metrics = context.resources.displayMetrics
        controls.x = controls.x.coerceIn(0, (metrics.widthPixels-controls.width).coerceAtLeast(0))
        controls.y = controls.y.coerceIn(dp(28), (metrics.heightPixels - controls.height - dp(32)).coerceAtLeast(dp(28)))
        try { if (panel.isAttachedToWindow) wm.updateViewLayout(panel, controls) } catch (_: RuntimeException) { failure(); close() }
    }
    private fun beginDrawing() {
        if (input != null) return
        hidden = false; annotation.visibility = View.VISIBLE
        try {
            input = GuidanceCanvas(context).also { it.state = state; it.tool = GuidanceTool.PEN; it.renderConfirmed = false; it.onPut = put
                wm.addView(it, params(-1, -1)) }
            // Last-added window is on top, so controls must remain above drawing input.
            wm.removeView(panel); wm.addView(panel, controls); expanded = true; rebuild()
        } catch (_: RuntimeException) { failure(); finishDrawing() }
    }
    private fun finishDrawing() { input?.let { remove(it) }; input = null }
    private fun remove(view: View) { try { if (view.isAttachedToWindow) wm.removeViewImmediate(view) } catch (_: RuntimeException) { failure() } }
    override fun close() {
        if (closed) return
        closed = true; handler.removeCallbacksAndMessages(null); finishDrawing(); remove(panel); remove(annotation)
    }
}
