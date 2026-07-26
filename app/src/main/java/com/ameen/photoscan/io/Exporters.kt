package com.ameen.photoscan.io

import com.ameen.photoscan.recon.Mesh
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ExportFormat(val label: String, val ext: String, val mime: String, val blurb: String) {
    GLB("GLB", "glb", "model/gltf-binary", "Opens anywhere. Keeps colour."),
    PLY("PLY", "ply", "application/octet-stream", "Binary. For MeshLab, CloudCompare, Blender."),
    OBJ("OBJ", "obj", "text/plain", "Text. Widest CAD support, largest file.")
}

object Exporters {

    fun export(mesh: Mesh, dir: File, baseName: String, format: ExportFormat): File {
        dir.mkdirs()
        val out = File(dir, "$baseName.${format.ext}")
        when (format) {
            ExportFormat.GLB -> writeGlb(mesh, out)
            ExportFormat.PLY -> writePly(mesh, out)
            ExportFormat.OBJ -> writeObj(mesh, out)
        }
        return out
    }

    // ------------------------------------------------------------------ GLB

    private fun writeGlb(mesh: Mesh, file: File) {
        val nv = mesh.vertexCount
        val ni = mesh.indices.size

        val posBytes = nv * 12
        val nrmBytes = nv * 12
        val colBytes = nv * 4          // COLOR_0 as normalised unsigned bytes
        val idxBytes = ni * 4

        fun pad4(x: Int) = (4 - (x % 4)) % 4
        val posOff = 0
        val nrmOff = posOff + posBytes
        val colOff = nrmOff + nrmBytes
        val idxOff = colOff + colBytes + pad4(colOff + colBytes)
        val binLen = idxOff + idxBytes

        val bin = ByteBuffer.allocate(binLen + pad4(binLen)).order(ByteOrder.LITTLE_ENDIAN)
        for (v in mesh.positions) bin.putFloat(v)
        for (v in mesh.normals) bin.putFloat(v)
        bin.position(colOff)
        for (i in 0 until nv) {
            bin.put(mesh.colors[i * 3]); bin.put(mesh.colors[i * 3 + 1]); bin.put(mesh.colors[i * 3 + 2])
            bin.put(255.toByte())
        }
        bin.position(idxOff)
        for (v in mesh.indices) bin.putInt(v)
        val binArray = bin.array()

        val (mn, mx) = mesh.bounds()

        val json = JSONObject().apply {
            put("asset", JSONObject().put("version", "2.0").put("generator", "PhotoScan for Android"))
            put("scene", 0)
            put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            put("nodes", JSONArray().put(JSONObject().put("mesh", 0).put("name", "scan")))
            put("materials", JSONArray().put(JSONObject().apply {
                put("name", "vertexColor")
                put("doubleSided", true)
                put("pbrMetallicRoughness", JSONObject().apply {
                    put("baseColorFactor", JSONArray().put(1.0).put(1.0).put(1.0).put(1.0))
                    put("metallicFactor", 0.0)
                    put("roughnessFactor", 0.95)
                })
            }))
            put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(
                JSONObject().apply {
                    put("attributes", JSONObject()
                        .put("POSITION", 0).put("NORMAL", 1).put("COLOR_0", 2))
                    put("indices", 3)
                    put("material", 0)
                    put("mode", 4)
                }
            ))))
            put("buffers", JSONArray().put(JSONObject().put("byteLength", binArray.size)))
            put("bufferViews", JSONArray().apply {
                put(bufferView(posOff, posBytes, 34962))
                put(bufferView(nrmOff, nrmBytes, 34962))
                put(bufferView(colOff, colBytes, 34962))
                put(bufferView(idxOff, idxBytes, 34963))
            })
            put("accessors", JSONArray().apply {
                put(JSONObject().apply {
                    put("bufferView", 0); put("componentType", 5126); put("count", nv); put("type", "VEC3")
                    put("min", JSONArray().put(mn[0].toDouble()).put(mn[1].toDouble()).put(mn[2].toDouble()))
                    put("max", JSONArray().put(mx[0].toDouble()).put(mx[1].toDouble()).put(mx[2].toDouble()))
                })
                put(JSONObject().put("bufferView", 1).put("componentType", 5126).put("count", nv).put("type", "VEC3"))
                put(JSONObject().put("bufferView", 2).put("componentType", 5121)
                    .put("count", nv).put("type", "VEC4").put("normalized", true))
                put(JSONObject().put("bufferView", 3).put("componentType", 5125).put("count", ni).put("type", "SCALAR"))
            })
        }

        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - (jsonBytes.size % 4)) % 4
        if (jsonPad > 0) jsonBytes += ByteArray(jsonPad) { 0x20.toByte() }

        val total = 12 + 8 + jsonBytes.size + 8 + binArray.size
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { o ->
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0x46546C67); header.putInt(2); header.putInt(total)
            o.write(header.array())

            val jc = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            jc.putInt(jsonBytes.size); jc.putInt(0x4E4F534A)
            o.write(jc.array()); o.write(jsonBytes)

            val bc = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            bc.putInt(binArray.size); bc.putInt(0x004E4942)
            o.write(bc.array()); o.write(binArray)
        }
    }

    private fun bufferView(offset: Int, length: Int, target: Int) = JSONObject().apply {
        put("buffer", 0); put("byteOffset", offset); put("byteLength", length); put("target", target)
    }

    // ------------------------------------------------------------------ PLY

    private fun writePly(mesh: Mesh, file: File) {
        val nv = mesh.vertexCount
        val nt = mesh.triangleCount
        val header = buildString {
            append("ply\n")
            append("format binary_little_endian 1.0\n")
            append("comment created by PhotoScan for Android\n")
            append("element vertex $nv\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("element face $nt\n")
            append("property list uchar int vertex_indices\n")
            append("end_header\n")
        }

        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { o ->
            o.write(header.toByteArray(Charsets.US_ASCII))
            val vb = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until nv) {
                vb.clear()
                vb.putFloat(mesh.positions[i * 3]); vb.putFloat(mesh.positions[i * 3 + 1]); vb.putFloat(mesh.positions[i * 3 + 2])
                vb.putFloat(mesh.normals[i * 3]); vb.putFloat(mesh.normals[i * 3 + 1]); vb.putFloat(mesh.normals[i * 3 + 2])
                vb.put(mesh.colors[i * 3]); vb.put(mesh.colors[i * 3 + 1]); vb.put(mesh.colors[i * 3 + 2])
                o.write(vb.array(), 0, 27)
            }
            val fb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            for (t in 0 until nt) {
                fb.clear()
                fb.put(3)
                fb.putInt(mesh.indices[t * 3]); fb.putInt(mesh.indices[t * 3 + 1]); fb.putInt(mesh.indices[t * 3 + 2])
                o.write(fb.array(), 0, 13)
            }
        }
    }

    // ------------------------------------------------------------------ OBJ

    private fun writeObj(mesh: Mesh, file: File) {
        file.bufferedWriter(Charsets.US_ASCII, 1 shl 16).use { w ->
            w.write("# PhotoScan for Android\n")
            w.write("# vertex colours are appended to each v line\n")
            val nv = mesh.vertexCount
            for (i in 0 until nv) {
                val r = (mesh.colors[i * 3].toInt() and 0xFF) / 255f
                val g = (mesh.colors[i * 3 + 1].toInt() and 0xFF) / 255f
                val b = (mesh.colors[i * 3 + 2].toInt() and 0xFF) / 255f
                w.write("v ${f(mesh.positions[i * 3])} ${f(mesh.positions[i * 3 + 1])} ${f(mesh.positions[i * 3 + 2])} ${f(r)} ${f(g)} ${f(b)}\n")
            }
            for (i in 0 until nv) {
                w.write("vn ${f(mesh.normals[i * 3])} ${f(mesh.normals[i * 3 + 1])} ${f(mesh.normals[i * 3 + 2])}\n")
            }
            var i = 0
            while (i < mesh.indices.size) {
                val a = mesh.indices[i] + 1
                val b = mesh.indices[i + 1] + 1
                val c = mesh.indices[i + 2] + 1
                w.write("f $a//$a $b//$b $c//$c\n")
                i += 3
            }
        }
    }

    private fun f(v: Float): String = String.format(java.util.Locale.US, "%.5f", v)
}
