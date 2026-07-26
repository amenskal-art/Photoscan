package com.ameen.photoscan.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class ModelSurfaceView(context: Context) : GLSurfaceView(context) {

    val meshRenderer = MeshRenderer()

    /** Fired the first time the user touches the model, so the UI can drop out of auto-spin. */
    var onInteract: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var pointers = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(meshRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                pointers = 1
                meshRenderer.autoSpin = false
                onInteract?.invoke()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pointers = e.pointerCount
                lastSpan = span(e)
                lastMidX = (e.getX(0) + e.getX(1)) / 2f
                lastMidY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val s = span(e)
                    if (lastSpan > 0f && s > 0f) {
                        val scale = lastSpan / s
                        meshRenderer.distance = (meshRenderer.distance * scale).coerceIn(0.25f, 6f)
                    }
                    lastSpan = s

                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    val k = meshRenderer.distance * 0.0022f
                    meshRenderer.panX -= (mx - lastMidX) * k
                    meshRenderer.panY += (my - lastMidY) * k
                    lastMidX = mx; lastMidY = my
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    meshRenderer.yaw -= dx * 0.008f
                    meshRenderer.pitch = (meshRenderer.pitch + dy * 0.008f).coerceIn(-1.45f, 1.45f)
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastSpan = 0f
                val remaining = e.pointerCount - 1
                if (remaining == 1) {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointers = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun resetView() {
        meshRenderer.yaw = 0.6f
        meshRenderer.pitch = 0.35f
        meshRenderer.distance = 1.6f
        meshRenderer.panX = 0f
        meshRenderer.panY = 0f
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(abs(e.getX(0) - e.getX(1)), abs(e.getY(0) - e.getY(1)))
    }
}
