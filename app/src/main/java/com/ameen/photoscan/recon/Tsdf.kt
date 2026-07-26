package com.ameen.photoscan.recon

import android.opengl.GLES31
import com.ameen.photoscan.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Truncated signed distance field held entirely in GPU memory.
 *
 * Integration dispatches one invocation per voxel per keyframe. Because each invocation owns exactly
 * one voxel there are no atomics and no read-modify-write races, which is what makes this fast enough
 * to fuse 150 frames in a few seconds on a phone GPU.
 *
 * Layout, both buffers indexed by z*N*N + y*N + x:
 *   tsdf[i]  = weight(16) | signedDistanceFixed(16)   distance normalised to [-1,1] over the truncation band
 *   color[i] = weight(8) | b(8) | g(8) | r(8)
 */
class Tsdf(val n: Int, val origin: FloatArray, val voxel: Float) {

    val voxelCount = n * n * n

    var tsdfBuf = 0; private set
    var colorBuf = 0; private set

    private var integrateProg = 0
    private var clearProg = 0
    private var depthTex = 0
    private var confTex = 0
    private var colorTex = 0

    private val clearSrc = """
        #version 310 es
        layout(local_size_x = 64) in;
        layout(std430, binding = 0) buffer Tsdf  { uint d[]; } vol;
        layout(std430, binding = 1) buffer Color { uint d[]; } col;
        uniform int uCount;
        void main() {
            uint i = gl_GlobalInvocationID.x;
            if (i >= uint(uCount)) return;
            vol.d[i] = 0u;
            col.d[i] = 0u;
        }
    """.trimIndent()

    private val integrateSrc = """
        #version 310 es
        layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;

        layout(std430, binding = 0) buffer Tsdf  { uint d[]; } vol;
        layout(std430, binding = 1) buffer Color { uint d[]; } col;

        uniform highp usampler2D uDepth;
        uniform highp usampler2D uConf;
        uniform sampler2D        uColor;

        uniform int   uN;
        uniform vec3  uOrigin;
        uniform float uVoxel;
        uniform mat4  uView;        // world -> camera, OpenGL convention (camera looks down -Z)
        uniform vec4  uIntr;        // fx, fy, cx, cy in depth pixels
        uniform ivec2 uDepthSize;
        uniform float uMu;          // truncation band, metres
        uniform float uMinDepth;
        uniform float uMaxDepth;
        uniform float uMinConf;
        uniform int   uUseColor;

        float depthAt(ivec2 p) {
            uint raw = texelFetch(uDepth, p, 0).r & 8191u;   // low 13 bits are millimetres
            return float(raw) * 0.001;
        }

        void main() {
            ivec3 g = ivec3(gl_GlobalInvocationID);
            if (g.x >= uN || g.y >= uN || g.z >= uN) return;
            int idx = g.z * uN * uN + g.y * uN + g.x;

            vec3 p = uOrigin + (vec3(g) + 0.5) * uVoxel;
            vec4 pc = uView * vec4(p, 1.0);

            float z = -pc.z;                     // distance in front of the camera
            if (z < uMinDepth || z > uMaxDepth) return;

            float u = uIntr.x * (pc.x / z) + uIntr.z;
            float v = uIntr.y * (-pc.y / z) + uIntr.w;
            ivec2 px = ivec2(floor(vec2(u, v)));
            if (px.x < 1 || px.y < 1 || px.x >= uDepthSize.x - 1 || px.y >= uDepthSize.y - 1) return;

            float d = depthAt(px);
            if (d <= 0.0) return;

            float conf = float(texelFetch(uConf, px, 0).r) / 255.0;
            if (conf < uMinConf) return;

            // Reject silhouette pixels. A large local depth gradient means this texel straddles an
            // edge, and interpolating across it is what produces flying-pixel skirts.
            float dl = depthAt(px + ivec2(-1, 0));
            float dr = depthAt(px + ivec2( 1, 0));
            float du = depthAt(px + ivec2( 0,-1));
            float dd = depthAt(px + ivec2( 0, 1));
            float grad = max(max(abs(dl - d), abs(dr - d)), max(abs(du - d), abs(dd - d)));
            if (dl <= 0.0 || dr <= 0.0 || du <= 0.0 || dd <= 0.0) return;
            if (grad > 0.06 + 0.03 * d) return;

            float sdf = d - z;
            if (sdf < -uMu) return;                          // behind the surface, occluded

            float tsdf = clamp(sdf / uMu, -1.0, 1.0);

            // Near measurements are far more trustworthy than far ones.
            float falloff = clamp(1.0 / (1.0 + 6.0 * (d - uMinDepth) * (d - uMinDepth)), 0.08, 1.0);
            float w = conf * falloff;
            if (w <= 0.0) return;

            uint packed = vol.d[idx];
            float oldW = float(packed >> 16);
            float oldT = float((int(packed & 65535u) << 16) >> 16) / 32767.0;

            float newW = min(oldW + w, 250.0);
            float newT = (oldT * oldW + tsdf * w) / max(newW, 1e-6);
            newT = clamp(newT, -1.0, 1.0);

            vol.d[idx] = (uint(newW) << 16) | (uint(int(newT * 32767.0)) & 65535u);

            // Colour only matters within the band that will actually become geometry.
            if (uUseColor == 1 && abs(sdf) < uMu) {
                vec2 uv = vec2((u + 0.5) / float(uDepthSize.x), (v + 0.5) / float(uDepthSize.y));
                vec3 c = texture(uColor, uv).rgb;
                uint pc0 = col.d[idx];
                float cw = float((pc0 >> 24) & 255u);
                vec3 oldC = vec3(float(pc0 & 255u), float((pc0 >> 8) & 255u), float((pc0 >> 16) & 255u)) / 255.0;
                float ncw = min(cw + w, 250.0);
                vec3 nc = (oldC * cw + c * w) / max(ncw, 1e-6);
                col.d[idx] = uint(clamp(nc.r, 0.0, 1.0) * 255.0)
                           | (uint(clamp(nc.g, 0.0, 1.0) * 255.0) << 8)
                           | (uint(clamp(nc.b, 0.0, 1.0) * 255.0) << 16)
                           | (uint(ncw) << 24);
            }
        }
    """.trimIndent()

    fun allocate() {
        val bytes = voxelCount.toLong() * 4L
        val ids = IntArray(2)
        GLES31.glGenBuffers(2, ids, 0)
        tsdfBuf = ids[0]; colorBuf = ids[1]

        for (b in ids) {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, b)
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes.toInt(), null, GLES31.GL_DYNAMIC_COPY)
            val err = GLES31.glGetError()
            if (err != GLES31.GL_NO_ERROR) {
                throw OutOfMemoryError("GPU refused a ${bytes / (1024 * 1024)} MB voxel buffer. Try a lower quality.")
            }
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        clearProg = GlUtil.computeProgram(clearSrc)
        integrateProg = GlUtil.computeProgram(integrateSrc)

        depthTex = GlUtil.genTexture()
        confTex = GlUtil.genTexture()
        colorTex = GlUtil.genTexture()
        for (t in intArrayOf(depthTex, confTex, colorTex)) {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, t)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            val filter = if (t == colorTex) GLES31.GL_LINEAR else GLES31.GL_NEAREST
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, filter)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, filter)
        }

        clear()
    }

    private fun clear() {
        GLES31.glUseProgram(clearProg)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, tsdfBuf)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, colorBuf)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(clearProg, "uCount"), voxelCount)
        val groups = (voxelCount + 63) / 64
        var done = 0
        // Split into chunks so we never exceed the per-dispatch work group limit.
        val maxGroups = 65535
        while (done < groups) {
            val batch = minOf(maxGroups, groups - done)
            GLES31.glDispatchCompute(batch, 1, 1)
            done += batch
        }
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    /**
     * Fuse one keyframe.
     * @param depth  raw DEPTH16 values, dw*dh
     * @param conf   0..255 confidence, dw*dh
     * @param rgba   RGBA8 colour image, cw*ch (may be null to skip colour)
     */
    fun integrate(
        view: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        depth: ShortArray, conf: ByteArray, dw: Int, dh: Int,
        rgba: ByteArray?, cw: Int, ch: Int,
        mu: Float, minDepth: Float, maxDepth: Float, minConf: Float
    ) {
        uploadDepth(depth, dw, dh)
        uploadConf(conf, dw, dh)
        val useColor = rgba != null && cw > 0 && ch > 0
        if (useColor) uploadColor(rgba!!, cw, ch)

        GLES31.glUseProgram(integrateProg)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, tsdfBuf)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, colorBuf)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, depthTex)
        GLES31.glUniform1i(loc("uDepth"), 0)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE1)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, confTex)
        GLES31.glUniform1i(loc("uConf"), 1)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE2)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, colorTex)
        GLES31.glUniform1i(loc("uColor"), 2)

        GLES31.glUniform1i(loc("uN"), n)
        GLES31.glUniform3f(loc("uOrigin"), origin[0], origin[1], origin[2])
        GLES31.glUniform1f(loc("uVoxel"), voxel)
        GLES31.glUniformMatrix4fv(loc("uView"), 1, false, view, 0)
        GLES31.glUniform4f(loc("uIntr"), fx, fy, cx, cy)
        GLES31.glUniform2i(loc("uDepthSize"), dw, dh)
        GLES31.glUniform1f(loc("uMu"), mu)
        GLES31.glUniform1f(loc("uMinDepth"), minDepth)
        GLES31.glUniform1f(loc("uMaxDepth"), maxDepth)
        GLES31.glUniform1f(loc("uMinConf"), minConf)
        GLES31.glUniform1i(loc("uUseColor"), if (useColor) 1 else 0)

        val g = (n + 3) / 4
        GLES31.glDispatchCompute(g, g, g)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun loc(name: String) = GLES31.glGetUniformLocation(integrateProg, name)

    private fun uploadDepth(depth: ShortArray, w: Int, h: Int) {
        val bb = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        bb.asShortBuffer().put(depth, 0, minOf(depth.size, w * h))
        bb.position(0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, depthTex)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 2)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_R16UI, w, h, 0,
            GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, bb
        )
    }

    private fun uploadConf(conf: ByteArray, w: Int, h: Int) {
        val bb = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder())
        bb.put(conf, 0, minOf(conf.size, w * h)); bb.position(0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, confTex)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_R8UI, w, h, 0,
            GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_BYTE, bb
        )
    }

    private fun uploadColor(rgba: ByteArray, w: Int, h: Int) {
        val bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        bb.put(rgba, 0, minOf(rgba.size, w * h * 4)); bb.position(0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, colorTex)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 4)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA8, w, h, 0,
            GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, bb
        )
    }

    fun release() {
        if (tsdfBuf != 0) GLES31.glDeleteBuffers(2, intArrayOf(tsdfBuf, colorBuf), 0)
        if (integrateProg != 0) GLES31.glDeleteProgram(integrateProg)
        if (clearProg != 0) GLES31.glDeleteProgram(clearProg)
        GLES31.glDeleteTextures(3, intArrayOf(depthTex, confTex, colorTex), 0)
        tsdfBuf = 0; colorBuf = 0
    }
}
