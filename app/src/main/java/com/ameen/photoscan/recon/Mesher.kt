package com.ameen.photoscan.recon

import android.opengl.GLES30
import android.opengl.GLES31
import com.ameen.photoscan.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts the zero level set of a [Tsdf] using marching tetrahedra.
 *
 * Tetrahedra rather than cubes on purpose: each cube splits into six tets around its main diagonal,
 * every tet has only three topological cases, and the result is watertight by construction. There is
 * no 256-entry lookup table to get subtly wrong, and no ambiguous-face problem. The cost is roughly
 * twice the triangle count of marching cubes, which vertex welding largely absorbs.
 *
 * The volume is meshed in Z slabs so peak GPU and heap memory stay bounded regardless of resolution.
 */
class Mesher(private val tsdf: Tsdf) {

    companion object {
        const val SLAB_THICKNESS = 16
        const val MAX_TRIS_PER_SLAB = 400_000
        const val FLOATS_PER_VERTEX = 9   // px py pz nx ny nz r g b
        const val FLOATS_PER_TRI = FLOATS_PER_VERTEX * 3
    }

    private var prog = 0
    private var vertBuf = 0
    private var counterBuf = 0
    var overflowed = false; private set

    private val src = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;

        layout(std430, binding = 0) readonly  buffer Tsdf    { uint d[]; } vol;
        layout(std430, binding = 1) readonly  buffer Color   { uint d[]; } col;
        layout(std430, binding = 2) writeonly buffer Verts   { float v[]; } outv;
        layout(std430, binding = 3)           buffer Counter { uint n; }   cnt;

        uniform int   uN;
        uniform vec3  uOrigin;
        uniform float uVoxel;
        uniform float uMinWeight;
        uniform int   uMaxTris;
        uniform int   uZStart;
        uniform int   uZCount;

        struct Vtx { vec3 p; vec3 n; vec3 c; };

        int vi(ivec3 c) { return c.z * uN * uN + c.y * uN + c.x; }

        float tsdfAt(ivec3 c) {
            uint p = vol.d[vi(c)];
            return float((int(p & 65535u) << 16) >> 16) / 32767.0;
        }
        float wAt(ivec3 c) { return float(vol.d[vi(c)] >> 16); }

        vec3 colAt(ivec3 c) {
            uint p = col.d[vi(c)];
            return vec3(float(p & 255u), float((p >> 8) & 255u), float((p >> 16) & 255u)) / 255.0;
        }

        vec3 gradAt(ivec3 c) {
            ivec3 q = clamp(c, ivec3(1), ivec3(uN - 2));
            return vec3(
                tsdfAt(q + ivec3(1,0,0)) - tsdfAt(q - ivec3(1,0,0)),
                tsdfAt(q + ivec3(0,1,0)) - tsdfAt(q - ivec3(0,1,0)),
                tsdfAt(q + ivec3(0,0,1)) - tsdfAt(q - ivec3(0,0,1))
            );
        }

        vec3 cellPos(ivec3 c) { return uOrigin + (vec3(c) + 0.5) * uVoxel; }

        Vtx onEdge(ivec3 ca, float va, ivec3 cb, float vb) {
            float denom = va - vb;
            float t = (abs(denom) < 1e-7) ? 0.5 : clamp(va / denom, 0.0, 1.0);
            Vtx r;
            r.p = mix(cellPos(ca), cellPos(cb), t);
            r.n = mix(gradAt(ca), gradAt(cb), t);
            r.c = mix(colAt(ca), colAt(cb), t);
            return r;
        }

        void put(uint base, Vtx v) {
            outv.v[base + 0u] = v.p.x; outv.v[base + 1u] = v.p.y; outv.v[base + 2u] = v.p.z;
            outv.v[base + 3u] = v.n.x; outv.v[base + 4u] = v.n.y; outv.v[base + 5u] = v.n.z;
            outv.v[base + 6u] = v.c.r; outv.v[base + 7u] = v.c.g; outv.v[base + 8u] = v.c.b;
        }

        void emit(Vtx a, Vtx b, Vtx c) {
            // Outward is the direction the distance field increases, so orient against the gradient.
            vec3 gn = cross(b.p - a.p, c.p - a.p);
            vec3 an = a.n + b.n + c.n;
            bool flip = dot(gn, an) < 0.0;

            uint t = atomicAdd(cnt.n, 1u);
            if (t >= uint(uMaxTris)) return;
            uint base = t * 27u;
            put(base, a);
            if (flip) { put(base + 9u, c); put(base + 18u, b); }
            else      { put(base + 9u, b); put(base + 18u, c); }
        }

        void main() {
            // Six tetrahedra sharing the cube diagonal 0-7, one per axis permutation.
            int tets[24] = int[24](
                0,1,3,7,  0,1,5,7,  0,2,3,7,
                0,2,6,7,  0,4,5,7,  0,4,6,7
            );

            ivec3 g = ivec3(gl_GlobalInvocationID);
            if (g.x >= uN - 1 || g.y >= uN - 1 || g.z >= uZCount) return;
            int z = uZStart + g.z;
            if (z >= uN - 1) return;
            ivec3 cell = ivec3(g.x, g.y, z);

            ivec3 corner[8];
            float val[8];
            for (int i = 0; i < 8; i++) {
                ivec3 c = cell + ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
                corner[i] = c;
                if (wAt(c) < uMinWeight) return;   // unobserved corner: leave a hole rather than invent surface
                val[i] = tsdfAt(c);
            }

            for (int t = 0; t < 6; t++) {
                int inside[4]; int outside[4];
                int ni = 0; int no = 0;
                for (int k = 0; k < 4; k++) {
                    int id = tets[t * 4 + k];
                    if (val[id] < 0.0) { inside[ni] = id; ni++; }
                    else { outside[no] = id; no++; }
                }
                if (ni == 0 || ni == 4) continue;

                if (ni == 1) {
                    int a = inside[0];
                    emit(
                        onEdge(corner[a], val[a], corner[outside[0]], val[outside[0]]),
                        onEdge(corner[a], val[a], corner[outside[1]], val[outside[1]]),
                        onEdge(corner[a], val[a], corner[outside[2]], val[outside[2]])
                    );
                } else if (ni == 3) {
                    int a = outside[0];
                    emit(
                        onEdge(corner[inside[0]], val[inside[0]], corner[a], val[a]),
                        onEdge(corner[inside[1]], val[inside[1]], corner[a], val[a]),
                        onEdge(corner[inside[2]], val[inside[2]], corner[a], val[a])
                    );
                } else {
                    // Two in, two out: the surface cuts the tet as a quad.
                    int a = inside[0];  int b = inside[1];
                    int c = outside[0]; int d = outside[1];
                    Vtx q0 = onEdge(corner[a], val[a], corner[c], val[c]);
                    Vtx q1 = onEdge(corner[a], val[a], corner[d], val[d]);
                    Vtx q2 = onEdge(corner[b], val[b], corner[d], val[d]);
                    Vtx q3 = onEdge(corner[b], val[b], corner[c], val[c]);
                    emit(q0, q1, q2);
                    emit(q0, q2, q3);
                }
            }
        }
    """.trimIndent()

    fun prepare() {
        prog = GlUtil.computeProgram(src, "surface extraction")

        val ids = IntArray(2)
        GLES31.glGenBuffers(2, ids, 0)
        vertBuf = ids[0]; counterBuf = ids[1]

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, vertBuf)
        GLES31.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            MAX_TRIS_PER_SLAB * FLOATS_PER_TRI * 4,
            null, GLES31.GL_DYNAMIC_COPY
        )
        if (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            throw OutOfMemoryError("Could not allocate the mesh output buffer.")
        }

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counterBuf)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4, null, GLES31.GL_DYNAMIC_COPY)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    /**
     * Runs every slab and streams triangles into [builder].
     * @param onProgress called with 0..1 as slabs complete.
     */
    fun extract(builder: MeshBuilder, minWeight: Float, onProgress: (Float) -> Unit) {
        val n = tsdf.n
        val slabs = ((n - 1) + SLAB_THICKNESS - 1) / SLAB_THICKNESS
        val zeroBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            putInt(0); position(0)
        }

        val p0 = FloatArray(3); val n0 = FloatArray(3); val c0 = FloatArray(3)
        val p1 = FloatArray(3); val n1 = FloatArray(3); val c1 = FloatArray(3)
        val p2 = FloatArray(3); val n2 = FloatArray(3); val c2 = FloatArray(3)

        for (s in 0 until slabs) {
            val zStart = s * SLAB_THICKNESS
            val zCount = minOf(SLAB_THICKNESS, (n - 1) - zStart)
            if (zCount <= 0) break

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counterBuf)
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, zeroBuf)

            GLES31.glUseProgram(prog)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, tsdf.tsdfBuf)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, tsdf.colorBuf)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, vertBuf)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, counterBuf)

            GLES31.glUniform1i(u("uN"), n)
            GLES31.glUniform3f(u("uOrigin"), tsdf.origin[0], tsdf.origin[1], tsdf.origin[2])
            GLES31.glUniform1f(u("uVoxel"), tsdf.voxel)
            GLES31.glUniform1f(u("uMinWeight"), minWeight)
            GLES31.glUniform1i(u("uMaxTris"), MAX_TRIS_PER_SLAB)
            GLES31.glUniform1i(u("uZStart"), zStart)
            GLES31.glUniform1i(u("uZCount"), zCount)

            val gx = (n + 3) / 4
            val gz = (zCount + 3) / 4
            GLES31.glDispatchCompute(gx, gx, gz)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

            val produced = readCounter()
            val tris = minOf(produced, MAX_TRIS_PER_SLAB)
            if (produced > MAX_TRIS_PER_SLAB) overflowed = true

            if (tris > 0) {
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, vertBuf)
                val bytes = tris.toLong() * FLOATS_PER_TRI * 4
                val mapped = GLES30.glMapBufferRange(
                    GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes.toInt(), GLES30.GL_MAP_READ_BIT
                ) as? ByteBuffer
                if (mapped != null) {
                    val fb = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer()
                    val chunk = FloatArray(FLOATS_PER_TRI)
                    for (t in 0 until tris) {
                        fb.position(t * FLOATS_PER_TRI)
                        fb.get(chunk, 0, FLOATS_PER_TRI)
                        unpack(chunk, 0, p0, n0, c0)
                        unpack(chunk, 9, p1, n1, c1)
                        unpack(chunk, 18, p2, n2, c2)
                        builder.addTriangle(p0, n0, c0, p1, n1, c1, p2, n2, c2)
                    }
                    GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                }
            }

            onProgress((s + 1).toFloat() / slabs)
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun unpack(src: FloatArray, off: Int, p: FloatArray, n: FloatArray, c: FloatArray) {
        p[0] = src[off]; p[1] = src[off + 1]; p[2] = src[off + 2]
        n[0] = src[off + 3]; n[1] = src[off + 4]; n[2] = src[off + 5]
        c[0] = src[off + 6]; c[1] = src[off + 7]; c[2] = src[off + 8]
    }

    private fun readCounter(): Int {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counterBuf)
        val b = GLES30.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, GLES30.GL_MAP_READ_BIT
        ) as? ByteBuffer ?: return 0
        val v = b.order(ByteOrder.nativeOrder()).int
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        return v
    }

    private fun u(name: String) = GLES31.glGetUniformLocation(prog, name)

    fun release() {
        if (prog != 0) GLES31.glDeleteProgram(prog)
        if (vertBuf != 0) GLES31.glDeleteBuffers(2, intArrayOf(vertBuf, counterBuf), 0)
        prog = 0; vertBuf = 0; counterBuf = 0
    }
}
