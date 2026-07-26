package com.ameen.photoscan.recon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLES31
import android.opengl.Matrix
import com.ameen.photoscan.data.Keyframe
import com.ameen.photoscan.data.Project
import com.ameen.photoscan.gl.GlUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plane-sweep multi-view stereo — the dense half of classical photogrammetry, on the phone GPU.
 *
 * For every pixel of a reference photo, sweep candidate depths through the scan volume; at each
 * candidate, project the pixel into 2-3 neighbouring photos and score the match with ZNCC over a
 * 5x5 patch (zero-mean normalised cross-correlation: robust to exposure differences between shots).
 * The depth that makes the actual photographs agree wins. No depth sensor, no learned model —
 * triangulation from parallax, which is what photogrammetry is.
 *
 * ARCore contributes only the camera poses (its visual-inertial odometry), which stand in for the
 * structure-from-motion stage exactly as COLMAP's known-poses mode does.
 */
class Mvs(
    private val project: Project,
    private val keyframes: List<Keyframe>,
    rgbW: Int,
    rgbH: Int,
    rgbFx: Float,
    rgbFy: Float,
    rgbCx: Float,
    rgbCy: Float,
    private val roiCenter: FloatArray?,
    private val roiHalf: Float
) {
    companion object {
        const val WORK_W = 384
        const val PLANES = 96
        const val PLANES_PER_DISPATCH = 12
        const val MAX_NEIGHBORS = 3
        const val MIN_BASELINE = 0.015f
        const val MAX_BASELINE = 0.15f
        const val IDEAL_BASELINE = 0.05f
        const val MIN_ZNCC = 0.55f
        const val MIN_GAP = 0.03f
    }

    val workW = WORK_W
    val workH = ((WORK_W.toFloat() * rgbH / rgbW).roundToInt()).coerceAtLeast(64)
    private val fx = rgbFx * workW / rgbW
    private val fy = rgbFy * workH / rgbH
    private val cx = rgbCx * workW / rgbW
    private val cy = rgbCy * workH / rgbH

    class Result(
        val depthMm: ShortArray,
        val conf: ByteArray,
        val w: Int,
        val h: Int,
        val fx: Float, val fy: Float, val cx: Float, val cy: Float,
        val rgba: ByteArray,
        val validFraction: Float
    )

    private var sweepProg = 0
    private var finalizeProg = 0
    private var medianProg = 0
    private var bestBuf = 0        // vec4 per pixel: bestCost, bestInvDepth, secondCost, bestPlane
    private var depthBuf = 0       // float per pixel, metres, 0 = rejected
    private var outBuf = 0         // float per pixel after median
    private var confBuf = 0        // float per pixel 0..1
    private var refTex = 0
    private val nbTex = IntArray(MAX_NEIGHBORS)

    private val grayCache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > 16
    }

    // ------------------------------------------------------------------ GLSL

    private val sweepSrc = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 8, local_size_y = 8) in;

        layout(std430, binding = 0) buffer Best { vec4 d[]; } best;

        uniform sampler2D uRef;
        uniform sampler2D uN0;
        uniform sampler2D uN1;
        uniform sampler2D uN2;
        uniform int   uNumNb;
        uniform mat4  uRel[3];        // ref camera space -> neighbour camera space
        uniform vec4  uIntr;          // fx fy cx cy, one physical camera for all shots
        uniform ivec2 uSize;
        uniform float uInvNear;
        uniform float uInvFar;
        uniform int   uPlaneStart;
        uniform int   uPlaneCount;
        uniform int   uTotalPlanes;

        const int R = 2;              // 5x5 patch
        const float NPX = 25.0;

        float refP[25];               // per-invocation private
        float rMean;
        float rStd;

        float patchCost(sampler2D tex, vec2 uvPix) {
            float s = 0.0; float s2 = 0.0; float sc = 0.0;
            int k = 0;
            for (int dy = -R; dy <= R; dy++) {
                for (int dx = -R; dx <= R; dx++) {
                    vec2 uv = (uvPix + vec2(float(dx), float(dy))) / vec2(uSize);
                    float v = texture(tex, uv).r;
                    s += v; s2 += v * v; sc += v * refP[k];
                    k++;
                }
            }
            float m = s / NPX;
            float varN = s2 / NPX - m * m;
            if (varN < 1e-5) return -3.0;
            return (sc / NPX - m * rMean) / (sqrt(varN) * rStd);
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uSize.x || p.y >= uSize.y) return;
            int idx = p.y * uSize.x + p.x;
            bool border = p.x < R + 1 || p.y < R + 1 || p.x >= uSize.x - R - 1 || p.y >= uSize.y - R - 1;

            if (uPlaneStart == 0) best.d[idx] = vec4(-2.0, 0.0, -2.0, -9.0);
            if (border) return;

            float s = 0.0; float s2 = 0.0;
            int k = 0;
            for (int dy = -R; dy <= R; dy++) {
                for (int dx = -R; dx <= R; dx++) {
                    float v = texelFetch(uRef, p + ivec2(dx, dy), 0).r;
                    refP[k++] = v; s += v; s2 += v * v;
                }
            }
            rMean = s / NPX;
            float rVar = s2 / NPX - rMean * rMean;
            if (rVar < 1e-5) return;               // textureless patch: photogrammetry has nothing to match
            rStd = sqrt(rVar);

            vec4 cur = best.d[idx];

            for (int pl = 0; pl < uPlaneCount; pl++) {
                int plane = uPlaneStart + pl;
                float invD = mix(uInvNear, uInvFar, (float(plane) + 0.5) / float(uTotalPlanes));
                float d = 1.0 / invD;

                // Reference pixel at this depth, in reference camera space (OpenGL axes).
                vec3 xc = vec3(
                    (float(p.x) + 0.5 - uIntr.z) / uIntr.x * d,
                    -((float(p.y) + 0.5 - uIntr.w) / uIntr.y * d),
                    -d
                );

                float costSum = 0.0;
                int valid = 0;
                for (int nb = 0; nb < uNumNb; nb++) {
                    vec4 xn = uRel[nb] * vec4(xc, 1.0);
                    float z = -xn.z;
                    if (z < 0.05) continue;
                    vec2 uv = vec2(
                        uIntr.x * (xn.x / z) + uIntr.z,
                        uIntr.y * (-xn.y / z) + uIntr.w
                    );
                    if (uv.x < float(R + 1) || uv.y < float(R + 1) ||
                        uv.x >= float(uSize.x - R - 1) || uv.y >= float(uSize.y - R - 1)) continue;

                    float c;
                    if (nb == 0) c = patchCost(uN0, uv);
                    else if (nb == 1) c = patchCost(uN1, uv);
                    else c = patchCost(uN2, uv);
                    if (c > -2.0) { costSum += c; valid++; }
                }
                if (valid == 0) continue;
                float c = costSum / float(valid);

                if (c > cur.x) {
                    if (abs(float(plane) - cur.w) > 2.5) cur.z = cur.x;
                    cur.x = c; cur.y = invD; cur.w = float(plane);
                } else if (c > cur.z && abs(float(plane) - cur.w) > 2.5) {
                    cur.z = c;
                }
            }
            best.d[idx] = cur;
        }
    """.trimIndent()

    private val finalizeSrc = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 64) in;
        layout(std430, binding = 0) readonly buffer Best  { vec4 d[]; } best;
        layout(std430, binding = 1) writeonly buffer Depth { float d[]; } depth;
        layout(std430, binding = 2) writeonly buffer Conf  { float d[]; } conf;
        uniform int uCount;
        uniform int uTotalPlanes;
        uniform float uMinC;
        uniform float uMinGap;
        void main() {
            uint i = gl_GlobalInvocationID.x;
            if (i >= uint(uCount)) return;
            vec4 b = best.d[i];
            bool ok = b.x >= uMinC &&
                      (b.x - b.z) >= uMinGap &&
                      b.w > 0.5 && b.w < float(uTotalPlanes) - 1.5 &&
                      b.y > 0.0;
            depth.d[i] = ok ? (1.0 / b.y) : 0.0;
            float c = clamp((b.x - 0.5) * 2.0, 0.0, 1.0) * clamp((b.x - b.z) * 8.0, 0.2, 1.0);
            conf.d[i] = ok ? c : 0.0;
        }
    """.trimIndent()

    private val medianSrc = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(std430, binding = 0) readonly buffer DIn  { float d[]; } din;
        layout(std430, binding = 1) writeonly buffer DOut { float d[]; } dout;
        uniform ivec2 uSize;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uSize.x || p.y >= uSize.y) return;
            int idx = p.y * uSize.x + p.x;
            if (p.x < 1 || p.y < 1 || p.x >= uSize.x - 1 || p.y >= uSize.y - 1) {
                dout.d[idx] = 0.0;
                return;
            }
            float v[9];
            int n = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    float d = din.d[(p.y + dy) * uSize.x + (p.x + dx)];
                    if (d > 0.0) { v[n] = d; n++; }
                }
            }
            // A lone depth with almost no valid neighbours is speckle, not surface.
            if (n < 4 || din.d[idx] <= 0.0) { dout.d[idx] = 0.0; return; }
            for (int a = 1; a < n; a++) {
                float key = v[a];
                int b = a - 1;
                while (b >= 0 && v[b] > key) { v[b + 1] = v[b]; b--; }
                v[b + 1] = key;
            }
            dout.d[idx] = v[n / 2];
        }
    """.trimIndent()

    // ------------------------------------------------------------------ setup

    fun prepare() {
        sweepProg = GlUtil.computeProgram(sweepSrc, "plane sweep")
        finalizeProg = GlUtil.computeProgram(finalizeSrc, "sweep finalize")
        medianProg = GlUtil.computeProgram(medianSrc, "depth median")

        val n = workW * workH
        val ids = IntArray(4)
        GLES31.glGenBuffers(4, ids, 0)
        bestBuf = ids[0]; depthBuf = ids[1]; outBuf = ids[2]; confBuf = ids[3]
        alloc(bestBuf, n * 16)
        alloc(depthBuf, n * 4)
        alloc(outBuf, n * 4)
        alloc(confBuf, n * 4)

        refTex = GlUtil.genTexture()
        for (k in 0 until MAX_NEIGHBORS) nbTex[k] = GlUtil.genTexture()
        for (t in intArrayOf(refTex) + nbTex) {
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, t)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
        }
    }

    private fun alloc(buf: Int, bytes: Int) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES31.GL_DYNAMIC_COPY)
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            throw OutOfMemoryError("GPU refused an MVS buffer of $bytes bytes.")
        }
    }

    // ------------------------------------------------------------------ per-frame

    /** Null when this keyframe has no usable neighbours or its image cannot be read. */
    fun depthFor(refIndex: Int): Result? {
        val ref = keyframes[refIndex]
        val neighbors = selectNeighbors(refIndex)
        if (neighbors.isEmpty()) return null

        val refBmp = decodeExact(project.rgbFile(ref.index), workW, workH) ?: return null
        val refGray = grayOf(ref.index, refBmp)
        val rgba = rgbaOf(refBmp)
        refBmp.recycle()
        if (refGray == null) return null

        uploadGray(refTex, refGray)
        val rel = FloatArray(16 * MAX_NEIGHBORS)
        val invRef = FloatArray(16)
        Matrix.invertM(invRef, 0, ref.viewMatrix, 0)
        var nbCount = 0
        for (nb in neighbors) {
            val gray = grayOf(nb.index, null) ?: continue
            uploadGray(nbTex[nbCount], gray)
            Matrix.multiplyMM(rel, nbCount * 16, nb.viewMatrix, 0, invRef, 0)
            nbCount++
            if (nbCount == MAX_NEIGHBORS) break
        }
        if (nbCount == 0) return null

        val (near, far) = sweepRange(ref)

        // ---- sweep, chunked to stay under the GPU watchdog ----
        GLES31.glUseProgram(sweepProg)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bestBuf)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, refTex)
        GLES31.glUniform1i(u(sweepProg, "uRef"), 0)
        for (k in 0 until MAX_NEIGHBORS) {
            GLES31.glActiveTexture(GLES31.GL_TEXTURE1 + k)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, nbTex[k])
            GLES31.glUniform1i(u(sweepProg, "uN$k"), 1 + k)
        }
        GLES31.glUniform1i(u(sweepProg, "uNumNb"), nbCount)
        GLES31.glUniformMatrix4fv(u(sweepProg, "uRel[0]"), MAX_NEIGHBORS, false, rel, 0)
        GLES31.glUniform4f(u(sweepProg, "uIntr"), fx, fy, cx, cy)
        GLES31.glUniform2i(u(sweepProg, "uSize"), workW, workH)
        GLES31.glUniform1f(u(sweepProg, "uInvNear"), 1f / near)
        GLES31.glUniform1f(u(sweepProg, "uInvFar"), 1f / far)
        GLES31.glUniform1i(u(sweepProg, "uTotalPlanes"), PLANES)

        val gx = (workW + 7) / 8
        val gy = (workH + 7) / 8
        var start = 0
        while (start < PLANES) {
            val count = min(PLANES_PER_DISPATCH, PLANES - start)
            GLES31.glUniform1i(u(sweepProg, "uPlaneStart"), start)
            GLES31.glUniform1i(u(sweepProg, "uPlaneCount"), count)
            GLES31.glDispatchCompute(gx, gy, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            start += count
        }

        // ---- accept + speckle-filter ----
        val n = workW * workH
        GLES31.glUseProgram(finalizeProg)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bestBuf)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, depthBuf)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, confBuf)
        GLES31.glUniform1i(u(finalizeProg, "uCount"), n)
        GLES31.glUniform1i(u(finalizeProg, "uTotalPlanes"), PLANES)
        GLES31.glUniform1f(u(finalizeProg, "uMinC"), MIN_ZNCC)
        GLES31.glUniform1f(u(finalizeProg, "uMinGap"), MIN_GAP)
        GLES31.glDispatchCompute((n + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        GLES31.glUseProgram(medianProg)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, depthBuf)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, outBuf)
        GLES31.glUniform2i(u(medianProg, "uSize"), workW, workH)
        GLES31.glDispatchCompute(gx, gy, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

        // ---- read back ----
        val depths = readFloats(outBuf, n) ?: return null
        val confs = readFloats(confBuf, n) ?: return null
        val mm = ShortArray(n)
        val cb = ByteArray(n)
        var valid = 0
        for (i in 0 until n) {
            val d = depths[i]
            if (d in 0.12f..5.0f) {
                mm[i] = (d * 1000f).toInt().toShort()
                cb[i] = ((confs[i].coerceIn(0f, 1f)) * 255f).toInt().toByte()
                valid++
            }
        }
        return Result(mm, cb, workW, workH, fx, fy, cx, cy, rgba, valid.toFloat() / n)
    }

    // ------------------------------------------------------------------ pieces

    private fun selectNeighbors(refIndex: Int): List<Keyframe> {
        val ref = keyframes[refIndex]
        val fRef = forwardOf(ref)
        val scored = ArrayList<Pair<Float, Keyframe>>()
        for (kf in keyframes) {
            if (kf.index == ref.index) continue
            val b = dist(ref.camPosition, kf.camPosition)
            if (b < MIN_BASELINE || b > MAX_BASELINE) continue
            val fN = forwardOf(kf)
            val dot = fRef[0] * fN[0] + fRef[1] * fN[1] + fRef[2] * fN[2]
            if (dot < 0.86f) continue      // > ~30 degrees apart: patches no longer comparable
            scored.add(abs(b - IDEAL_BASELINE) to kf)
        }
        scored.sortBy { it.first }
        return scored.take(MAX_NEIGHBORS).map { it.second }
    }

    private fun forwardOf(kf: Keyframe): FloatArray {
        val m = kf.viewMatrix
        val f = floatArrayOf(-m[2], -m[6], -m[10])
        val l = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
        if (l > 1e-6f) { f[0] /= l; f[1] /= l; f[2] /= l }
        return f
    }

    private fun sweepRange(ref: Keyframe): Pair<Float, Float> {
        val c = roiCenter
        if (c != null && roiHalf > 0f) {
            val d = dist(ref.camPosition, c)
            val near = max(0.12f, d - roiHalf * 1.9f)
            val far = min(5.0f, d + roiHalf * 1.9f)
            if (far > near + 0.05f) return near to far
        }
        return 0.2f to 2.5f
    }

    private fun grayOf(index: Int, decoded: Bitmap?): ByteArray? {
        grayCache[index]?.let { return it }
        val bmp = decoded ?: decodeExact(project.rgbFile(index), workW, workH) ?: return null
        val n = workW * workH
        val px = IntArray(n)
        bmp.getPixels(px, 0, workW, 0, 0, workW, workH)
        val g = ByteArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val lum = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            g[i] = lum.toByte()
        }
        if (decoded == null) bmp.recycle()
        grayCache[index] = g
        return g
    }

    private fun rgbaOf(bmp: Bitmap): ByteArray {
        val bb = ByteBuffer.allocate(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(bb)
        return bb.array()
    }

    private fun uploadGray(tex: Int, gray: ByteArray) {
        val bb = ByteBuffer.allocateDirect(gray.size).order(ByteOrder.nativeOrder())
        bb.put(gray); bb.position(0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, tex)
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 1)
        GLES31.glTexImage2D(
            GLES31.GL_TEXTURE_2D, 0, GLES31.GL_R8, workW, workH, 0,
            GLES31.GL_RED, GLES31.GL_UNSIGNED_BYTE, bb
        )
    }

    private fun readFloats(buf: Int, count: Int): FloatArray? {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf)
        val mapped = GLES30.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, count * 4, GLES30.GL_MAP_READ_BIT
        ) as? ByteBuffer ?: return null
        val out = FloatArray(count)
        mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(out)
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return out
    }

    private fun decodeExact(f: File, w: Int, h: Int): Bitmap? {
        if (!f.exists()) return null
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, probe)
        if (probe.outWidth <= 0) return null
        var sample = 1
        while (max(probe.outWidth, probe.outHeight) / (sample * 2) >= max(w, h)) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        if (raw.width == w && raw.height == h) return raw
        val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
        if (scaled != raw) raw.recycle()
        return scaled
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun u(prog: Int, name: String) = GLES31.glGetUniformLocation(prog, name)

    fun release() {
        if (sweepProg != 0) GLES31.glDeleteProgram(sweepProg)
        if (finalizeProg != 0) GLES31.glDeleteProgram(finalizeProg)
        if (medianProg != 0) GLES31.glDeleteProgram(medianProg)
        if (bestBuf != 0) GLES31.glDeleteBuffers(4, intArrayOf(bestBuf, depthBuf, outBuf, confBuf), 0)
        GLES31.glDeleteTextures(1 + MAX_NEIGHBORS, intArrayOf(refTex) + nbTex, 0)
        sweepProg = 0; finalizeProg = 0; medianProg = 0; bestBuf = 0
    }
}
