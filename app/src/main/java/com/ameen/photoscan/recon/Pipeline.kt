package com.ameen.photoscan.recon

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
    private const val MIN_CONF = 0.25f

    /**
     * Runs the whole reconstruction on the calling thread, which must not be the main thread.
     * Creates and destroys its own GL context.
     *
     * Depth source: plane-sweep multi-view stereo over the captured photographs — classical
     * photogrammetry, with ARCore's poses standing in for structure-from-motion. Frames without
     * usable neighbours contribute nothing rather than falling back to sensor depth.
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
            // A scan box anchored during capture is authoritative: it is why a small object gets the
            // full voxel resolution instead of sharing it with the whole room behind it.
            val rc = project.roiCenter
            val bounds = if (rc != null && project.roiHalf > 0f) {
                val h = project.roiHalf
                floatArrayOf(rc[0] - h, rc[1] - h, rc[2] - h) to
                        floatArrayOf(rc[0] + h, rc[1] + h, rc[2] + h)
            } else {
                estimateBounds(project, keyframes)
            } ?: run {
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

            // The photogrammetry core: dense stereo per keyframe, fused into the volume.
            val intr = rgbIntrinsics(project, keyframes[0])
            if (intr.w <= 0 || intr.h <= 0) {
                emit(ReconProgress.Failed("Could not read the captured photos."))
                return
            }
            val mvs = Mvs(
                project, keyframes,
                intr.w, intr.h, intr.fx, intr.fy, intr.cx, intr.cy,
                project.roiCenter, project.roiHalf
            )
            var matchedFrames = 0
            var fracSum = 0f
            try {
                mvs.prepare()
                keyframes.forEachIndexed { i, kf ->
                    val f = 0.05f + 0.60f * (i.toFloat() / keyframes.size)
                    emit(ReconProgress.Stage("Matching · shot ${i + 1} of ${keyframes.size}", f))
                    val r = mvs.depthFor(i)
                    if (r != null && r.validFraction > 0.01f) {
                        tsdf.integrate(
                            kf.viewMatrix,
                            r.fx, r.fy, r.cx, r.cy,
                            r.depthMm, r.conf, r.w, r.h,
                            r.rgba, r.w, r.h,
                            mu, MIN_DEPTH, MAX_DEPTH, MIN_CONF
                        )
                        matchedFrames++
                        fracSum += r.validFraction
                    }
                }
            } finally {
                mvs.release()
            }
            if (matchedFrames < 4) {
                emit(ReconProgress.Failed(
                    "Photo-consistency matched almost nothing across ${keyframes.size} shots. " +
                        "The subject needs visible surface texture and steady light — " +
                        "plain, shiny or transparent surfaces cannot be triangulated from photos."
                ))
                return
            }
            val avgPct = (fracSum / matchedFrames * 100).toInt()
            emit(ReconProgress.Stage("Matched $matchedFrames shots · $avgPct% pixel coverage", 0.66f))

            emit(ReconProgress.Stage("Building surface", 0.68f))
            mesher = Mesher(tsdf)
            mesher.prepare()

            val minWeight = 0.75f
            val builder = MeshBuilder(voxel * 0.35f)
            mesher.extract(builder, minWeight = minWeight) { p ->
                emit(ReconProgress.Stage("Building surface", 0.68f + 0.22f * p))
            }

            var mesh = builder.build()
            if (mesh.triangleCount == 0) {
                // Say where the data died, not just that it did.
                val (observed, nearSurface) = tsdf.stats(minWeight)
                val msg = when {
                    observed == 0 ->
                        "Fusion produced an empty volume (0 of ${tsdf.voxelCount} voxels observed). " +
                            "This is a bug in the app, not in your scanning — please report it with your phone model."
                    nearSurface == 0 ->
                        "$observed voxels were observed but no surface crossing was found. " +
                            "This is a bug in the app, not in your scanning — please report it with your phone model."
                    else ->
                        "A surface exists ($nearSurface voxels) but meshing produced no triangles. " +
                            "This is a bug in the app, not in your scanning — please report it with your phone model."
                }
                emit(ReconProgress.Failed(msg))
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
            neural?.close()
        }
    }

    // ---------------------------------------------------------------------

    private data class RgbIntr(
        val w: Int, val h: Int, val fx: Float, val fy: Float, val cx: Float, val cy: Float
    )

    /** RGB-image intrinsics, deriving them from depth intrinsics + JPEG bounds for old scans. */
    private fun rgbIntrinsics(p: Project, kf: Keyframe): RgbIntr {
        if (kf.rgbWidth > 0 && kf.rgbHeight > 0 && kf.rgbFx > 0f) {
            return RgbIntr(kf.rgbWidth, kf.rgbHeight, kf.rgbFx, kf.rgbFy, kf.rgbCx, kf.rgbCy)
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(p.rgbFile(kf.index).absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return RgbIntr(0, 0, 0f, 0f, 0f, 0f)
        val sx = opts.outWidth.toFloat() / kf.depthWidth
        val sy = opts.outHeight.toFloat() / kf.depthHeight
        return RgbIntr(opts.outWidth, opts.outHeight, kf.fx * sx, kf.fy * sy, kf.cx * sx, kf.cy * sy)
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
                    val raw = depth[k].toInt() and 0xFFFF
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
                    val vI = mesh.indices[i + k]
                    var m = remap[vI]
                    if (m < 0) {
                        m = pos.size / 3
                        remap[vI] = m
                        pos.add(mesh.positions[vI * 3]); pos.add(mesh.positions[vI * 3 + 1]); pos.add(mesh.positions[vI * 3 + 2])
                        nrm.add(mesh.normals[vI * 3]); nrm.add(mesh.normals[vI * 3 + 1]); nrm.add(mesh.normals[vI * 3 + 2])
                        col.add(mesh.colors[vI * 3]); col.add(mesh.colors[vI * 3 + 1]); col.add(mesh.colors[vI * 3 + 2])
                    }
                    idx.add(m)
                }
            }
            i += 3
        }

        return Mesh(pos.toFloatArray(), nrm.toFloatArray(), col.toByteArray(), idx.toIntArray())
    }
}
