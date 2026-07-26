package com.ameen.photoscan.recon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import com.ameen.photoscan.data.Keyframe
import com.ameen.photoscan.data.Project
import com.ameen.photoscan.data.Quality
import com.ameen.photoscan.gl.EglCompute
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

sealed class ReconProgress {
    data class Stage(val label: String, val fraction: Float) : ReconProgress()
    data class Done(val mesh: Mesh, val seconds: Float) : ReconProgress()
    data class Failed(val message: String) : ReconProgress()
}

object Pipeline {

    private const val MIN_DEPTH = 0.15f
    private const val MAX_DEPTH = 4.5f
    private const val MIN_CONF = 0.35f

    /**
     * Runs the whole reconstruction on the calling thread, which must not be the main thread.
     * Creates and destroys its own GL context.
     */
    fun run(project: Project, quality: Quality, emit: (ReconProgress) -> Unit) {
        val started = System.nanoTime()
        val keyframes = project.loadKeyframes()
        if (keyframes.size < 6) {
            emit(ReconProgress.Failed("Only ${keyframes.size} shots captured. Scan a bit more and try again."))
            return
        }

        val egl = EglCompute()
        var tsdf: Tsdf? = null
        var mesher: Mesher? = null
        try {
            egl.makeCurrent()

            emit(ReconProgress.Stage("Measuring the scene", 0.02f))
            val bounds = estimateBounds(project, keyframes)
                ?: run {
                    emit(ReconProgress.Failed("No usable depth data. The scene may be too far away or too dark."))
                    return
                }

            val (mn, mx) = bounds
            val extent = floatArrayOf(mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2])
            val maxDim = max(max(extent[0], extent[1]), extent[2]) * 1.06f + 0.02f
            val n = quality.gridSize
            val voxel = maxDim / n
            val center = floatArrayOf(
                (mn[0] + mx[0]) * 0.5f, (mn[1] + mx[1]) * 0.5f, (mn[2] + mx[2]) * 0.5f
            )
            val origin = floatArrayOf(
                center[0] - maxDim * 0.5f, center[1] - maxDim * 0.5f, center[2] - maxDim * 0.5f
            )
            val mu = max(3.2f * voxel, 0.010f)

            tsdf = Tsdf(n, origin, voxel)
            tsdf.allocate()

            keyframes.forEachIndexed { i, kf ->
                val f = 0.05f + 0.60f * (i.toFloat() / keyframes.size)
                emit(ReconProgress.Stage("Fusing shot ${i + 1} of ${keyframes.size}", f))
                integrateOne(project, kf, tsdf, mu)
            }

            emit(ReconProgress.Stage("Building surface", 0.68f))
            mesher = Mesher(tsdf)
            mesher.prepare()

            val builder = MeshBuilder(voxel * 0.35f)
            mesher.extract(builder, minWeight = 1.2f) { p ->
                emit(ReconProgress.Stage("Building surface", 0.68f + 0.22f * p))
            }

            var mesh = builder.build()
            if (mesh.triangleCount == 0) {
                emit(ReconProgress.Failed("Nothing solid was found. Try scanning closer, with more overlap."))
                return
            }

            emit(ReconProgress.Stage("Cleaning up", 0.93f))
            mesh = removeFloaters(mesh)

            emit(ReconProgress.Stage("Saving", 0.98f))
            mesh.save(project.meshFile)
            project.triangleCount = mesh.triangleCount
            project.quality = quality
            project.saveMeta(keyframes)

            val secs = (System.nanoTime() - started) / 1e9f
            emit(ReconProgress.Done(mesh, secs))
        } catch (oom: OutOfMemoryError) {
            emit(ReconProgress.Failed(oom.message ?: "Ran out of memory. Try a lower quality."))
        } catch (t: Throwable) {
            emit(ReconProgress.Failed(t.message ?: "Reconstruction failed."))
        } finally {
            mesher?.release()
            tsdf?.release()
            egl.release()
        }
    }

    // ---------------------------------------------------------------------

    /** Trimmed bounding box of all confident depth samples, so a few stray far points cannot blow up the grid. */
    private fun estimateBounds(p: Project, keyframes: List<Keyframe>): Pair<FloatArray, FloatArray>? {
        val xs = ArrayList<Float>(60_000)
        val ys = ArrayList<Float>(60_000)
        val zs = ArrayList<Float>(60_000)
        val model = FloatArray(16)
        val v = FloatArray(4)
        val o = FloatArray(4)

        val step = max(1, keyframes.size / 40)
        var i = 0
        while (i < keyframes.size) {
            val kf = keyframes[i]
            val depth = readShorts(p.depthFile(kf.index))
            if (depth != null) {
                val conf = p.confFile(kf.index).takeIf { it.exists() }?.readBytes()
                Matrix.invertM(model, 0, kf.viewMatrix, 0)
                val total = kf.depthWidth * kf.depthHeight
                val stride = max(1, total / 1200)
                var k = 0
                while (k < total && k < depth.size) {
                    val raw = depth[k].toInt() and 0x1FFF
                    val c = conf?.getOrNull(k)?.toInt()?.and(0xFF) ?: 255
                    if (raw != 0 && c > 120) {
                        val d = raw * 0.001f
                        if (d in MIN_DEPTH..MAX_DEPTH) {
                            val px = k % kf.depthWidth
                            val py = k / kf.depthWidth
                            v[0] = (px - kf.cx) / kf.fx * d
                            v[1] = -((py - kf.cy) / kf.fy * d)
                            v[2] = -d
                            v[3] = 1f
                            Matrix.multiplyMV(o, 0, model, 0, v, 0)
                            xs.add(o[0]); ys.add(o[1]); zs.add(o[2])
                        }
                    }
                    k += stride
                }
            }
            i += step
        }

        if (xs.size < 500) return null
        fun trimmed(list: ArrayList<Float>): Pair<Float, Float> {
            list.sort()
            val lo = list[(list.size * 0.02f).toInt()]
            val hi = list[min(list.size - 1, (list.size * 0.98f).toInt())]
            return lo to hi
        }
        val (x0, x1) = trimmed(xs)
        val (y0, y1) = trimmed(ys)
        val (z0, z1) = trimmed(zs)
        return floatArrayOf(x0, y0, z0) to floatArrayOf(x1, y1, z1)
    }

    private fun integrateOne(p: Project, kf: Keyframe, tsdf: Tsdf, mu: Float) {
        val depth = readShorts(p.depthFile(kf.index)) ?: return
        val conf = p.confFile(kf.index).takeIf { it.exists() }?.readBytes()
            ?: ByteArray(kf.depthWidth * kf.depthHeight) { 255.toByte() }

        var rgba: ByteArray? = null
        var cw = 0; var ch = 0
        val jpg = p.rgbFile(kf.index)
        if (jpg.exists()) {
            val bmp = decodeScaled(jpg, 640)
            if (bmp != null) {
                cw = bmp.width; ch = bmp.height
                val bb = ByteBuffer.allocate(cw * ch * 4).order(ByteOrder.nativeOrder())
                bmp.copyPixelsToBuffer(bb)
                rgba = bb.array()
                bmp.recycle()
            }
        }

        tsdf.integrate(
            kf.viewMatrix, kf.fx, kf.fy, kf.cx, kf.cy,
            depth, conf, kf.depthWidth, kf.depthHeight,
            rgba, cw, ch,
            mu, MIN_DEPTH, MAX_DEPTH, MIN_CONF
        )
    }

    private fun decodeScaled(f: File, maxDim: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, probe)
        var sample = 1
        while (max(probe.outWidth, probe.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    private fun readShorts(f: File): ShortArray? {
        if (!f.exists()) return null
        return runCatching {
            val bytes = ByteArray(f.length().toInt())
            DataInputStream(f.inputStream().buffered()).use { it.readFully(bytes) }
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(sb.remaining()).also { sb.get(it) }
        }.getOrNull()
    }

    /**
     * Depth sensors produce isolated blobs of noise floating near the subject. Keeping only
     * components that carry real surface area removes them without touching the model.
     */
    private fun removeFloaters(mesh: Mesh): Mesh {
        val nv = mesh.vertexCount
        if (nv == 0) return mesh
        val parent = IntArray(nv) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        var i = 0
        while (i < mesh.indices.size) {
            union(mesh.indices[i], mesh.indices[i + 1])
            union(mesh.indices[i + 1], mesh.indices[i + 2])
            i += 3
        }

        val triPerRoot = HashMap<Int, Int>()
        i = 0
        while (i < mesh.indices.size) {
            val r = find(mesh.indices[i])
            triPerRoot[r] = (triPerRoot[r] ?: 0) + 1
            i += 3
        }
        val biggest = triPerRoot.values.maxOrNull() ?: return mesh
        val threshold = max(24, (biggest * 0.02f).toInt())
        val keepRoots = triPerRoot.filterValues { it >= threshold }.keys
        if (keepRoots.size == triPerRoot.size) return mesh

        val remap = IntArray(nv) { -1 }
        val pos = ArrayList<Float>(nv)
        val nrm = ArrayList<Float>(nv)
        val col = ArrayList<Byte>(nv)
        val idx = ArrayList<Int>(mesh.indices.size)

        i = 0
        while (i < mesh.indices.size) {
            val a = mesh.indices[i]
            if (find(a) in keepRoots) {
                for (k in 0..2) {
                    val v = mesh.indices[i + k]
                    var m = remap[v]
                    if (m < 0) {
                        m = pos.size / 3
                        remap[v] = m
                        pos.add(mesh.positions[v * 3]); pos.add(mesh.positions[v * 3 + 1]); pos.add(mesh.positions[v * 3 + 2])
                        nrm.add(mesh.normals[v * 3]); nrm.add(mesh.normals[v * 3 + 1]); nrm.add(mesh.normals[v * 3 + 2])
                        col.add(mesh.colors[v * 3]); col.add(mesh.colors[v * 3 + 1]); col.add(mesh.colors[v * 3 + 2])
                    }
                    idx.add(m)
                }
            }
            i += 3
        }

        return Mesh(pos.toFloatArray(), nrm.toFloatArray(), col.toByteArray(), idx.toIntArray())
    }
}
