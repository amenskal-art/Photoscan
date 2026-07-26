package com.ameen.photoscan.recon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/** Indexed triangle mesh with per-vertex normals and colours. */
class Mesh(
    val positions: FloatArray,   // 3 per vertex
    val normals: FloatArray,     // 3 per vertex
    val colors: ByteArray,       // 3 per vertex, RGB
    val indices: IntArray
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = indices.size / 3

    fun bounds(): Pair<FloatArray, FloatArray> {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i < positions.size) {
            for (k in 0..2) {
                val v = positions[i + k]
                if (v < mn[k]) mn[k] = v
                if (v > mx[k]) mx[k] = v
            }
            i += 3
        }
        return mn to mx
    }

    fun save(f: File) {
        DataOutputStream(f.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(vertexCount)
            o.writeInt(indices.size)
            for (v in positions) o.writeFloat(v)
            for (v in normals) o.writeFloat(v)
            o.write(colors)
            for (v in indices) o.writeInt(v)
        }
    }

    companion object {
        private const val MAGIC = 0x50534D31 // "PSM1"

        fun load(f: File): Mesh? {
            if (!f.exists()) return null
            return runCatching {
                DataInputStream(f.inputStream().buffered(1 shl 16)).use { i ->
                    require(i.readInt() == MAGIC)
                    val nv = i.readInt()
                    val ni = i.readInt()
                    val pos = FloatArray(nv * 3) { i.readFloat() }
                    val nrm = FloatArray(nv * 3) { i.readFloat() }
                    val col = ByteArray(nv * 3)
                    i.readFully(col)
                    val idx = IntArray(ni) { i.readInt() }
                    Mesh(pos, nrm, col, idx)
                }
            }.getOrNull()
        }
    }
}

/**
 * Accumulates a triangle soup into an indexed mesh, merging vertices that land on the same
 * quantised position. Marching tetrahedra emits every vertex once per incident triangle, so this
 * typically removes about five sixths of them.
 */
class MeshBuilder(private val quantum: Float) {

    private val map = LongIntMap(1 shl 18)
    private var px = FloatArray(1 shl 16)
    private var nx = FloatArray(1 shl 16)
    private var cx = ByteArray(1 shl 16)
    private var vCount = 0
    private var idx = IntArray(1 shl 16)
    private var iCount = 0
    private val weights = IntArrayList()

    fun addTriangle(
        p0: FloatArray, n0: FloatArray, c0: FloatArray,
        p1: FloatArray, n1: FloatArray, c1: FloatArray,
        p2: FloatArray, n2: FloatArray, c2: FloatArray
    ) {
        val a = vertex(p0, n0, c0)
        val b = vertex(p1, n1, c1)
        val c = vertex(p2, n2, c2)
        if (a == b || b == c || a == c) return  // degenerate after welding
        pushIndex(a); pushIndex(b); pushIndex(c)
    }

    private fun vertex(p: FloatArray, n: FloatArray, c: FloatArray): Int {
        val kx = Math.round(p[0] / quantum).toLong()
        val ky = Math.round(p[1] / quantum).toLong()
        val kz = Math.round(p[2] / quantum).toLong()
        val key = (kx and 0x1FFFFF) or ((ky and 0x1FFFFF) shl 21) or ((kz and 0x1FFFFF) shl 42)

        val existing = map.get(key)
        if (existing >= 0) {
            val o = existing * 3
            nx[o] += n[0]; nx[o + 1] += n[1]; nx[o + 2] += n[2]
            weights.inc(existing)
            return existing
        }

        ensureVertexCapacity(vCount + 1)
        val o = vCount * 3
        px[o] = p[0]; px[o + 1] = p[1]; px[o + 2] = p[2]
        nx[o] = n[0]; nx[o + 1] = n[1]; nx[o + 2] = n[2]
        cx[o] = (c[0].coerceIn(0f, 1f) * 255f).toInt().toByte()
        cx[o + 1] = (c[1].coerceIn(0f, 1f) * 255f).toInt().toByte()
        cx[o + 2] = (c[2].coerceIn(0f, 1f) * 255f).toInt().toByte()
        weights.add(1)
        map.put(key, vCount)
        return vCount++
    }

    private fun pushIndex(v: Int) {
        if (iCount == idx.size) idx = idx.copyOf(idx.size * 2)
        idx[iCount++] = v
    }

    private fun ensureVertexCapacity(need: Int) {
        if (need * 3 <= px.size) return
        var cap = px.size
        while (need * 3 > cap) cap *= 2
        px = px.copyOf(cap); nx = nx.copyOf(cap); cx = cx.copyOf(cap)
    }

    fun build(): Mesh {
        val pos = px.copyOf(vCount * 3)
        val nrm = nx.copyOf(vCount * 3)
        val col = cx.copyOf(vCount * 3)
        var i = 0
        while (i < nrm.size) {
            val l = sqrt(nrm[i] * nrm[i] + nrm[i + 1] * nrm[i + 1] + nrm[i + 2] * nrm[i + 2])
            if (l > 1e-8f) { nrm[i] /= l; nrm[i + 1] /= l; nrm[i + 2] /= l } else { nrm[i + 1] = 1f }
            i += 3
        }
        return Mesh(pos, nrm, col, idx.copyOf(iCount))
    }

    val vertexCount get() = vCount
    val triangleCount get() = iCount / 3
}

private class IntArrayList {
    private var a = IntArray(1 shl 16)
    private var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun inc(i: Int) { if (i < n) a[i]++ }
}

/** Minimal open-addressed long -> int map. Avoids boxing across millions of lookups. */
class LongIntMap(initial: Int) {
    private var keys = LongArray(initial)
    private var vals = IntArray(initial) { -1 }
    private var used = BooleanArray(initial)
    private var size = 0
    private var mask = initial - 1

    init { require(Integer.bitCount(initial) == 1) }

    private fun hash(k: Long): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 32)
        return (h.toInt()) and mask
    }

    fun get(k: Long): Int {
        var i = hash(k)
        while (used[i]) {
            if (keys[i] == k) return vals[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(k: Long, v: Int) {
        if (size * 2 >= keys.size) grow()
        var i = hash(k)
        while (used[i]) {
            if (keys[i] == k) { vals[i] = v; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = k; vals[i] = v; size++
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        val cap = keys.size * 2
        keys = LongArray(cap); vals = IntArray(cap); used = BooleanArray(cap)
        mask = cap - 1; size = 0
        for (j in ok.indices) if (ou[j]) put(ok[j], ov[j])
    }
}
