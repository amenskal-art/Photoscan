package com.ameen.photoscan.gl

import android.opengl.GLES20
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {

    private const val TAG = "GlUtil"

    fun compile(type: Int, src: String): Int {
        val id = GLES31.glCreateShader(type)
        GLES31.glShaderSource(id, src)
        GLES31.glCompileShader(id)
        val ok = IntArray(1)
        GLES31.glGetShaderiv(id, GLES31.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(id)
            GLES31.glDeleteShader(id)
            throw RuntimeException("Shader compile failed: $log")
        }
        return id
    }

    fun program(vs: String, fs: String): Int {
        val v = compile(GLES31.GL_VERTEX_SHADER, vs)
        val f = compile(GLES31.GL_FRAGMENT_SHADER, fs)
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, v)
        GLES31.glAttachShader(p, f)
        GLES31.glLinkProgram(p)
        val ok = IntArray(1)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, ok, 0)
        GLES31.glDeleteShader(v)
        GLES31.glDeleteShader(f)
        if (ok[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(p)
            GLES31.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }
        return p
    }

    fun computeProgram(src: String): Int {
        val c = compile(GLES31.GL_COMPUTE_SHADER, src)
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, c)
        GLES31.glLinkProgram(p)
        val ok = IntArray(1)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, ok, 0)
        GLES31.glDeleteShader(c)
        if (ok[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(p)
            GLES31.glDeleteProgram(p)
            throw RuntimeException("Compute link failed: $log")
        }
        return p
    }

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
