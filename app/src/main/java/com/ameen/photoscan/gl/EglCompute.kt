package com.ameen.photoscan.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * A headless GLES 3.1 context. Reconstruction runs on its own thread with its own context so it
 * never competes with the camera preview or blocks the UI.
 *
 * The context must be requested as 3.1 specifically. EGL_CONTEXT_CLIENT_VERSION = 3 means 3.0, and
 * a 3.0 context rejects every `#version 310 es` shader — usually with an empty info log, which makes
 * it look like a shader bug when it is really a context bug.
 */
class EglCompute {

    companion object {
        private const val EGL_CONTEXT_MAJOR_VERSION = 0x3098  // same value as EGL_CONTEXT_CLIENT_VERSION
        private const val EGL_CONTEXT_MINOR_VERSION = 0x30FB
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** e.g. "OpenGL ES 3.2 v1.r32p1". Useful in error messages. */
    var glVersion: String = "unknown"
        private set

    fun makeCurrent() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "No suitable EGL config"
        }

        // Ask for 3.1 outright. Fall back to a plain 3.x context only if the driver refuses,
        // in which case the version check below will produce a clear message.
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(
                EGL_CONTEXT_MAJOR_VERSION, 3,
                EGL_CONTEXT_MINOR_VERSION, 1,
                EGL14.EGL_NONE
            ), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
        }
        check(context != EGL14.EGL_NO_CONTEXT) {
            "Could not create an OpenGL ES 3.1 context (eglCreateContext error 0x${
                Integer.toHexString(EGL14.eglGetError())
            })"
        }

        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        check(supportsCompute(glVersion)) {
            "This device gave a $glVersion context. Reconstruction needs OpenGL ES 3.1 or newer for " +
                "compute shaders."
        }
    }

    /** Parses "OpenGL ES 3.2 ..." and returns true for 3.1 and above. */
    private fun supportsCompute(v: String): Boolean {
        val m = Regex("""OpenGL ES (\d+)\.(\d+)""").find(v) ?: return false
        val major = m.groupValues[1].toIntOrNull() ?: return false
        val minor = m.groupValues[2].toIntOrNull() ?: return false
        return major > 3 || (major == 3 && minor >= 1)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }
}
