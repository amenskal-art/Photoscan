package com.ameen.photoscan.gl

import android.opengl.GLES20
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {

    const val TAG = "PhotoScanGL"

    /**
     * Some drivers fail a compile and return an empty info log, which leaves nothing to act on.
     * When that happens the GL version string is usually the real clue, so it goes in the message,
     * and the numbered source goes to Logcat under [TAG].
     */
    fun compile(type: Int, src: String, name: String): Int {
        val id = GLES31.glCreateShader(type)
        GLES31.glShaderSource(id, src)
        GLES31.glCompileShader(id)
        val ok = IntArray(1)
        GLES31.glGetShaderiv(id, GLES31.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = (GLES31.glGetShaderInfoLog(id) ?: "").trim()
            GLES31.glDeleteShader(id)
            Log.e(TAG, "--- $name failed to compile ---\n${numbered(src)}")
            val detail = if (log.isNotEmpty()) log
            else "driver gave no detail · GL_VERSION=${GLES20.glGetString(GLES20.GL_VERSION)}"
            throw RuntimeException("$name — $detail")
        }
        return id
    }

    fun program(vs: String, fs: String, name: String): Int {
        val v = compile(GLES31.GL_VERTEX_SHADER, vs, "$name vertex")
        val f = compile(GLES31.GL_FRAGMENT_SHADER, fs, "$name fragment")
        return link(name, v, f)
    }

    fun computeProgram(src: String, name: String): Int {
        val c = compile(GLES31.GL_COMPUTE_SHADER, src, "$name compute")
        return link(name, c)
    }

    private fun link(name: String, vararg shaders: Int): Int {
        val p = GLES31.glCreateProgram()
        shaders.forEach { GLES31.glAttachShader(p, it) }
        GLES31.glLinkProgram(p)
        val ok = IntArray(1)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, ok, 0)
        shaders.forEach { GLES31.glDeleteShader(it) }
        if (ok[0] == 0) {
            val log = (GLES31.glGetProgramInfoLog(p) ?: "").trim()
            GLES31.glDeleteProgram(p)
            throw RuntimeException("$name failed to link — ${log.ifEmpty { "no detail from driver" }}")
        }
        return p
    }

    private fun numbered(src: String): String =
        src.lineSequence().mapIndexed { i, l -> "%4d| %s".format(i + 1, l) }.joinToString("\n")

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }

    fun directBuffer(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    fun genBuffer(): Int {
        val b = IntArray(1)
        GLES31.glGenBuffers(1, b, 0)
        return b[0]
    }

    fun genTexture(): Int {
        val t = IntArray(1)
        GLES31.glGenTextures(1, t, 0)
        return t[0]
    }

    fun check(where: String) {
        var e = GLES20.glGetError()
        while (e != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
            e = GLES20.glGetError()
        }
    }
}
