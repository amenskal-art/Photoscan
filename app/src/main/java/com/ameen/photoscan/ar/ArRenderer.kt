package com.ameen.photoscan.ar

import android.opengl.GLES11Ext
import android.opengl.GLES31
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.ameen.photoscan.gl.GlUtil
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the ARCore camera stream, then overlays the point cloud accumulated so far so the user can
 * see literally what the scanner has and has not measured.
 */
class ArRenderer(
    private val engine: ArEngine,
    private val onFrameDrawn: () -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var displayRotation = 0
    @Volatile var viewportWidth = 1
    @Volatile var viewportHeight = 1
    @Volatile private var geometryDirty = true

    private var cameraTexId = 0
    private var bgProgram = 0
    private var pcProgram = 0
    private var pcVbo = 0

    private val ndcBuffer: FloatBuffer = GlUtil.floatBuffer(
        floatArrayOf(-1f, -1f, +1f, -1f, -1f, +1f, +1f, +1f)
    )
    private val texBuffer: FloatBuffer = GlUtil.floatBuffer(FloatArray(8))

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)

    private var uploadedPoints = 0

    private val bgVs = """
        #version 300 es
        in vec4 aPos;
        in vec2 aTex;
        out vec2 vTex;
        void main() { gl_Position = aPos; vTex = aTex; }
    """.trimIndent()

    private val bgFs = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vTex;
        uniform samplerExternalOES uTex;
        out vec4 frag;
        void main() { frag = texture(uTex, vTex); }
    """.trimIndent()

    private val pcVs = """
        #version 300 es
        in vec4 aPos;
        in vec3 aColor;
        uniform mat4 uViewProj;
        uniform float uSize;
        out vec3 vColor;
        void main() {
            gl_Position = uViewProj * aPos;
            gl_PointSize = clamp(uSize / max(gl_Position.w, 0.15), 2.0, 14.0);
            vColor = aColor;
        }
    """.trimIndent()

    private val pcFs = """
        #version 300 es
        precision mediump float;
        in vec3 vColor;
        out vec4 frag;
        void main() {
            vec2 d = gl_PointCoord - vec2(0.5);
            float r = dot(d, d);
            if (r > 0.25) discard;
            float a = smoothstep(0.25, 0.05, r);
            frag = vec4(vColor, a * 0.85);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES31.glClearColor(0.03f, 0.04f, 0.06f, 1f)

        cameraTexId = GlUtil.genTexture()
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)

        bgProgram = GlUtil.program(bgVs, bgFs)
        pcProgram = GlUtil.program(pcVs, pcFs)

        pcVbo = GlUtil.genBuffer()
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, pcVbo)
        GLES31.glBufferData(
            GLES31.GL_ARRAY_BUFFER,
            ArEngine.PREVIEW_POINT_BUDGET * 6 * 4,
            null,
            GLES31.GL_DYNAMIC_DRAW
        )
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
        uploadedPoints = 0
        geometryDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES31.glViewport(0, 0, width, height)
        geometryDirty = true
    }

    fun markGeometryDirty() { geometryDirty = true }

    override fun onDrawFrame(gl: GL10?) {
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT or GLES31.GL_DEPTH_BUFFER_BIT)
        val session: Session = engine.session ?: return

        try {
            session.setCameraTextureName(cameraTexId)
            if (geometryDirty) {
                session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                geometryDirty = false
            }

            val frame = session.update()

            if (frame.hasDisplayGeometryChanged()) {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcBuffer,
                    Coordinates2d.TEXTURE_NORMALIZED, texBuffer
                )
            }

            drawBackground()

            engine.onFrame(frame)

            val cam = frame.camera
            cam.getViewMatrix(view, 0)
            cam.getProjectionMatrix(proj, 0, 0.05f, 12f)
            Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
            drawPoints()

            onFrameDrawn()
        } catch (t: Throwable) {
            // Never let a bad frame kill the render loop.
        }
    }

    private fun drawBackground() {
        GLES31.glDisable(GLES31.GL_DEPTH_TEST)
        GLES31.glDepthMask(false)
        GLES31.glUseProgram(bgProgram)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(bgProgram, "uTex"), 0)

        val aPos = GLES31.glGetAttribLocation(bgProgram, "aPos")
        val aTex = GLES31.glGetAttribLocation(bgProgram, "aTex")
        ndcBuffer.position(0); texBuffer.position(0)
        GLES31.glVertexAttribPointer(aPos, 2, GLES31.GL_FLOAT, false, 0, ndcBuffer)
        GLES31.glVertexAttribPointer(aTex, 2, GLES31.GL_FLOAT, false, 0, texBuffer)
        GLES31.glEnableVertexAttribArray(aPos)
        GLES31.glEnableVertexAttribArray(aTex)
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
        GLES31.glDisableVertexAttribArray(aPos)
        GLES31.glDisableVertexAttribArray(aTex)

        GLES31.glDepthMask(true)
        GLES31.glEnable(GLES31.GL_DEPTH_TEST)
    }

    private fun drawPoints() {
        val count = engine.previewCount
        if (count == 0) return

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, pcVbo)
        if (count > uploadedPoints) {
            val newPoints = count - uploadedPoints
            val fb = GlUtil.floatBuffer(
                engine.previewPoints.copyOfRange(uploadedPoints * 6, count * 6)
            )
            GLES31.glBufferSubData(
                GLES31.GL_ARRAY_BUFFER,
                uploadedPoints * 6 * 4,
                newPoints * 6 * 4,
                fb
            )
            uploadedPoints = count
        }

        GLES31.glEnable(GLES31.GL_BLEND)
        GLES31.glBlendFunc(GLES31.GL_SRC_ALPHA, GLES31.GL_ONE_MINUS_SRC_ALPHA)
        GLES31.glUseProgram(pcProgram)
        GLES31.glUniformMatrix4fv(GLES31.glGetUniformLocation(pcProgram, "uViewProj"), 1, false, viewProj, 0)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(pcProgram, "uSize"), 3.2f)

        val aPos = GLES31.glGetAttribLocation(pcProgram, "aPos")
        val aCol = GLES31.glGetAttribLocation(pcProgram, "aColor")
        GLES31.glVertexAttribPointer(aPos, 3, GLES31.GL_FLOAT, false, 24, 0)
        GLES31.glVertexAttribPointer(aCol, 3, GLES31.GL_FLOAT, false, 24, 12)
        GLES31.glEnableVertexAttribArray(aPos)
        GLES31.glEnableVertexAttribArray(aCol)
        GLES31.glDrawArrays(GLES31.GL_POINTS, 0, count)
        GLES31.glDisableVertexAttribArray(aPos)
        GLES31.glDisableVertexAttribArray(aCol)
        GLES31.glDisable(GLES31.GL_BLEND)
        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, 0)
    }

    fun resetCloud() { uploadedPoints = 0 }
}
