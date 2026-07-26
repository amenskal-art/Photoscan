package com.ameen.photoscan.viewer

import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.ameen.photoscan.gl.GlUtil
import com.ameen.photoscan.recon.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

enum class ShadeMode { SHADED, COLOR, NORMALS, WIRE }

class MeshRenderer : GLSurfaceView.Renderer {

    @Volatile var mesh: Mesh? = null
        set(value) { field = value; uploadPending = true }

    @Volatile var mode = ShadeMode.COLOR
    @Volatile var autoSpin = true
    @Volatile var showGrid = true

    // Arcball state, driven by ModelSurfaceView.
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.35f
    @Volatile var distance = 1.6f
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    private var uploadPending = false
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0
    private var lineIbo = 0
    private var lineCount = 0
    private var gridVbo = 0
    private var gridVerts = 0

    private var meshProg = 0
    private var gridProg = 0

    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val mvp = FloatArray(16)
    private val center = FloatArray(3)
    private var radius = 0.5f
    private var lastFrameMs = 0L

    private val meshVs = """
        #version 300 es
        in vec3 aPos;
        in vec3 aNrm;
        in vec4 aCol;
        uniform mat4 uMvp;
        uniform mat4 uModel;
        out vec3 vNrm;
        out vec3 vCol;
        out vec3 vWorld;
        void main() {
            gl_Position = uMvp * vec4(aPos, 1.0);
            vNrm = mat3(uModel) * aNrm;
            vCol = aCol.rgb;
            vWorld = (uModel * vec4(aPos, 1.0)).xyz;
        }
    """.trimIndent()

    private val meshFs = """
        #version 300 es
        precision mediump float;
        in vec3 vNrm;
        in vec3 vCol;
        in vec3 vWorld;
        uniform int uMode;
        out vec4 frag;

        void main() {
            vec3 n = normalize(vNrm);
            if (!gl_FrontFacing) n = -n;

            // Two-light studio rig plus a cool sky bounce, so form reads without any texture.
            vec3 key = normalize(vec3(0.55, 0.75, 0.42));
            vec3 fill = normalize(vec3(-0.6, 0.15, 0.55));
            float kd = max(dot(n, key), 0.0);
            float fd = max(dot(n, fill), 0.0);
            float sky = 0.5 + 0.5 * n.y;

            vec3 ambient = mix(vec3(0.10, 0.12, 0.16), vec3(0.30, 0.36, 0.44), sky);
            vec3 light = ambient + kd * vec3(1.02, 0.99, 0.92) + fd * vec3(0.22, 0.30, 0.40);

            vec3 base;
            if (uMode == 0)      base = vec3(0.78);                    // shaded clay
            else if (uMode == 1) base = vCol;                          // captured colour
            else if (uMode == 2) { frag = vec4(n * 0.5 + 0.5, 1.0); return; }
            else                 { frag = vec4(0.30, 0.88, 0.70, 1.0); return; }

            vec3 c = base * light;
            c = c / (c + vec3(0.85));                                  // gentle rolloff, no clipping
            c = pow(c, vec3(0.4545));
            frag = vec4(c, 1.0);
        }
    """.trimIndent()

    private val gridVs = """
        #version 300 es
        in vec3 aPos;
        uniform mat4 uMvp;
        out float vFade;
        void main() {
            gl_Position = uMvp * vec4(aPos, 1.0);
            vFade = 1.0 - clamp(length(aPos.xz) / 1.2, 0.0, 1.0);
        }
    """.trimIndent()

    private val gridFs = """
        #version 300 es
        precision mediump float;
        in float vFade;
        out vec4 frag;
        void main() { frag = vec4(0.24, 0.33, 0.42, vFade * 0.55); }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES31.glClearColor(0.031f, 0.039f, 0.059f, 1f)
        GLES31.glEnable(GLES31.GL_DEPTH_TEST)
        meshProg = GlUtil.program(meshVs, meshFs, "model")
        gridProg = GlUtil.program(gridVs, gridFs, "grid")
        buildGrid()
        uploadPending = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES31.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.01f, 60f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT or GLES31.GL_DEPTH_BUFFER_BIT)

        if (uploadPending) { upload(); uploadPending = false }

        val now = System.currentTimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else (now - lastFrameMs) / 1000f
        lastFrameMs = now
        if (autoSpin) yaw += dt * 0.35f

        // Orbit around the model, normalised so any object fills the frame the same way.
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val d = distance * radius * 2.6f
        val eye = floatArrayOf(
            center[0] + d * cp * sy + panX,
            center[1] + d * sp + panY,
            center[2] + d * cp * cy
        )
        Matrix.setLookAtM(
            view, 0,
            eye[0], eye[1], eye[2],
            center[0] + panX, center[1] + panY, center[2],
            0f, 1f, 0f
        )
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, mvp, 0, model, 0)

        if (showGrid) drawGrid()
        drawMesh()
    }

    private fun drawMesh() {
        if (indexCount == 0) return
        GLES31.glUseProgram(meshProg)
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(meshProg, "uMvp"), 1, false, mvp, 0)
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(meshProg, "uModel"), 1, false, model, 0)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(meshProg, "uMode"), mode.ordinal)

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, vbo)
        GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, ibo)

        val aPos = GLES31.glGetAttribLocation(meshProg, "aPos")
        val aNrm = GLES31.glGetAttribLocation(meshProg, "aNrm")
        val aCol = GLES31.glGetAttribLocation(meshProg, "aCol")
        val stride = 28  // 3 pos + 3 nrm floats, then 4 colour bytes
        GLES31.glVertexAttribPointer(aPos, 3, GLES31.GL_FLOAT, false, stride, 0)
        GLES31.glVertexAttribPointer(aNrm, 3, GLES31.GL_FLOAT, false, stride, 12)
        GLES31.glVertexAttribPointer(aCol, 4, GLES31.GL_UNSIGNED_BYTE, true, stride, 24)
        GLES31.glEnableVertexAttribArray(aPos)
        GLES31.glEnableVertexAttribArray(aNrm)
        GLES31.glEnableVertexAttribArray(aCol)

        if (mode == ShadeMode.WIRE && ensureLineIndices()) {
            GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, lineIbo)
            GLES31.glDrawElements(GLES31.GL_LINES, lineCount, GLES31.GL_UNSIGNED_INT, 0)
        } else {
            GLES31.glDrawElements(GLES31.GL_TRIANGLES, indexCount, GLES31.GL_UNSIGNED_INT, 0)
        }

        GLES31.glDisableVertexAttribArray(aPos)
        GLES31.glDisableVertexAttribArray(aNrm)
        GLES31.glDisableVertexAttribArray(aCol)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
        GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawGrid() {
        if (gridVerts == 0) return
        GLES31.glEnable(GLES31.GL_BLEND)
        GLES31.glBlendFunc(GLES31.GL_SRC_ALPHA, GLES31.GL_ONE_MINUS_SRC_ALPHA)
        GLES31.glUseProgram(gridProg)

        // Grid sits at the bottom of the model, scaled to it.
        val gm = FloatArray(16)
        val gmvp = FloatArray(16)
        Matrix.setIdentityM(gm, 0)
        Matrix.translateM(gm, 0, center[0], center[1] - radius, center[2])
        Matrix.scaleM(gm, 0, radius * 2.2f, 1f, radius * 2.2f)
        Matrix.multiplyMM(gmvp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(gmvp, 0, gmvp, 0, gm, 0)
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(gridProg, "uMvp"), 1, false, gmvp, 0)

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, gridVbo)
        val a = GLES31.glGetAttribLocation(gridProg, "aPos")
        GLES31.glVertexAttribPointer(a, 3, GLES31.GL_FLOAT, false, 12, 0)
        GLES31.glEnableVertexAttribArray(a)
        GLES31.glDrawArrays(GLES31.GL_LINES, 0, gridVerts)
        GLES31.glDisableVertexAttribArray(a)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
        GLES31.glDisable(GLES31.GL_BLEND)
    }

    private fun buildGrid() {
        val lines = ArrayList<Float>()
        val n = 20
        val half = n / 2
        for (i in -half..half) {
            val t = i.toFloat() / half
            lines.addAll(listOf(t, 0f, -1f, t, 0f, 1f))
            lines.addAll(listOf(-1f, 0f, t, 1f, 0f, t))
        }
        val arr = lines.toFloatArray()
        gridVerts = arr.size / 3
        gridVbo = GlUtil.genBuffer()
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, gridVbo)
        GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, arr.size * 4, GlUtil.floatBuffer(arr), GLES31.GL_STATIC_DRAW)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Wireframe needs its own edge index buffer. Built the first time the mode is used rather than
     * up front, because on a dense scan it is comparable in size to the mesh itself.
     */
    private fun ensureLineIndices(): Boolean {
        if (lineIbo != 0) return true
        val m = mesh ?: return false
        if (m.triangleCount > 900_000) return false   // too heavy to be worth it

        val edges = IntArray(m.indices.size * 2)
        var o = 0
        var i = 0
        while (i < m.indices.size) {
            val a = m.indices[i]; val b = m.indices[i + 1]; val c = m.indices[i + 2]
            edges[o++] = a; edges[o++] = b
            edges[o++] = b; edges[o++] = c
            edges[o++] = c; edges[o++] = a
            i += 3
        }
        val bb = ByteBuffer.allocateDirect(edges.size * 4).order(ByteOrder.nativeOrder())
        bb.asIntBuffer().put(edges)
        bb.position(0)

        val id = IntArray(1)
        GLES31.glGenBuffers(1, id, 0)
        lineIbo = id[0]
        GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, lineIbo)
        GLES31.glBufferData(GLES31.GL_ELEMENT_ARRAY_BUFFER, edges.size * 4, bb, GLES31.GL_STATIC_DRAW)
        lineCount = edges.size
        return true
    }

    private fun upload() {
        val m = mesh ?: return
        if (vbo != 0) GLES31.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        if (lineIbo != 0) { GLES31.glDeleteBuffers(1, intArrayOf(lineIbo), 0); lineIbo = 0; lineCount = 0 }

        val nv = m.vertexCount
        val bb = ByteBuffer.allocateDirect(nv * 28).order(ByteOrder.nativeOrder())
        for (i in 0 until nv) {
            bb.putFloat(m.positions[i * 3]); bb.putFloat(m.positions[i * 3 + 1]); bb.putFloat(m.positions[i * 3 + 2])
            bb.putFloat(m.normals[i * 3]); bb.putFloat(m.normals[i * 3 + 1]); bb.putFloat(m.normals[i * 3 + 2])
            bb.put(m.colors[i * 3]); bb.put(m.colors[i * 3 + 1]); bb.put(m.colors[i * 3 + 2]); bb.put(255.toByte())
        }
        bb.position(0)

        val ib = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder())
        ib.asIntBuffer().put(m.indices)
        ib.position(0)

        val ids = IntArray(2)
        GLES31.glGenBuffers(2, ids, 0)
        vbo = ids[0]; ibo = ids[1]
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, vbo)
        GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, nv * 28, bb, GLES31.GL_STATIC_DRAW)
        GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES31.glBufferData(GLES31.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GLES31.GL_STATIC_DRAW)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
        GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, 0)
        indexCount = m.indices.size

        val (mn, mx) = m.bounds()
        center[0] = (mn[0] + mx[0]) / 2f
        center[1] = (mn[1] + mx[1]) / 2f
        center[2] = (mn[2] + mx[2]) / 2f
        radius = maxOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]) * 0.5f
        if (radius < 1e-4f) radius = 0.2f
    }
}
