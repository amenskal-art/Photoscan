package com.ameen.photoscan.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One captured viewpoint: an RGB frame, an ARCore depth map, and the camera pose that produced them.
 *
 * Poses come straight from ARCore's visual-inertial odometry, so they are already metrically scaled
 * and gravity aligned. viewMatrix is world -> camera in OpenGL convention (camera looks down -Z).
 * Intrinsics are expressed in *depth image* pixels so the reconstruction shaders can index directly.
 */
data class Keyframe(
    val index: Int,
    val timestampNs: Long,
    val viewMatrix: FloatArray,   // 16, column-major, world -> camera
    val camPosition: FloatArray,  // 3, camera centre in world space
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val depthWidth: Int,
    val depthHeight: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("timestampNs", timestampNs)
        put("view", JSONArray().also { a -> viewMatrix.forEach { a.put(it.toDouble()) } })
        put("pos", JSONArray().also { a -> camPosition.forEach { a.put(it.toDouble()) } })
        put("fx", fx.toDouble()); put("fy", fy.toDouble())
        put("cx", cx.toDouble()); put("cy", cy.toDouble())
        put("dw", depthWidth); put("dh", depthHeight)
    }

    companion object {
        fun fromJson(o: JSONObject): Keyframe {
            fun arr(name: String, n: Int): FloatArray {
                val a = o.getJSONArray(name)
                return FloatArray(n) { a.getDouble(it).toFloat() }
            }
            return Keyframe(
                index = o.getInt("index"),
                timestampNs = o.getLong("timestampNs"),
                viewMatrix = arr("view", 16),
                camPosition = arr("pos", 3),
                fx = o.getDouble("fx").toFloat(),
                fy = o.getDouble("fy").toFloat(),
                cx = o.getDouble("cx").toFloat(),
                cy = o.getDouble("cy").toFloat(),
                depthWidth = o.getInt("dw"),
                depthHeight = o.getInt("dh")
            )
        }
    }

    override fun equals(other: Any?) = other is Keyframe && other.index == index
    override fun hashCode() = index
}

/** Reconstruction detail. Voxel size is derived from the scanned bounding box divided by [gridSize]. */
enum class Quality(val label: String, val gridSize: Int, val maxTriangles: Int, val note: String) {
    FAST("Fast", 128, 700_000, "Under a minute. Good for checking coverage."),
    BALANCED("Balanced", 192, 1_400_000, "Recommended. Detail close to the depth sensor limit."),
    DETAIL("Detail", 256, 2_400_000, "Slowest, highest memory. Small objects only.")
}

class Project(val dir: File) {

    val id: String get() = dir.name
    val framesFile = File(dir, "frames.json")
    val meshFile = File(dir, "mesh.bin")
    val thumbFile = File(dir, "thumb.jpg")

    var name: String = id
    var createdAt: Long = System.currentTimeMillis()
    var keyframeCount: Int = 0
    var triangleCount: Int = 0
    var quality: Quality = Quality.BALANCED

    /** Scan volume anchored during capture: world-space centre and half edge length, metres. */
    var roiCenter: FloatArray? = null
    var roiHalf: Float = 0f

    fun rgbFile(i: Int) = File(dir, "rgb_%04d.jpg".format(i))
    fun depthFile(i: Int) = File(dir, "depth_%04d.bin".format(i))
    fun confFile(i: Int) = File(dir, "conf_%04d.bin".format(i))

    fun displayDate(): String =
        SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(createdAt))

    fun saveMeta(keyframes: List<Keyframe>) {
        dir.mkdirs()
        val root = JSONObject()
        root.put("name", name)
        root.put("createdAt", createdAt)
        root.put("quality", quality.name)
        root.put("triangleCount", triangleCount)
        roiCenter?.let { c ->
            if (roiHalf > 0f) {
                root.put("roi", JSONArray()
                    .put(c[0].toDouble()).put(c[1].toDouble()).put(c[2].toDouble())
                    .put(roiHalf.toDouble()))
            }
        }
        val arr = JSONArray()
        keyframes.forEach { arr.put(it.toJson()) }
        root.put("keyframes", arr)
        framesFile.writeText(root.toString())
        keyframeCount = keyframes.size
    }

    fun loadKeyframes(): List<Keyframe> {
        if (!framesFile.exists()) return emptyList()
        return try {
            val root = JSONObject(framesFile.readText())
            name = root.optString("name", id)
            createdAt = root.optLong("createdAt", createdAt)
            quality = runCatching { Quality.valueOf(root.optString("quality")) }.getOrDefault(Quality.BALANCED)
            triangleCount = root.optInt("triangleCount", 0)
            root.optJSONArray("roi")?.let { a ->
                if (a.length() == 4) {
                    roiCenter = floatArrayOf(
                        a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()
                    )
                    roiHalf = a.getDouble(3).toFloat()
                }
            }
            val arr = root.getJSONArray("keyframes")
            List(arr.length()) { Keyframe.fromJson(arr.getJSONObject(it)) }.also { keyframeCount = it.size }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun delete() = dir.deleteRecursively()
}

object ProjectStore {

    private fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "projects").apply { mkdirs() }

    fun exportsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "exports").apply { mkdirs() }

    fun create(ctx: Context): Project {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val p = Project(File(root(ctx), "scan-$stamp"))
        p.dir.mkdirs()
        p.name = "Scan " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        return p
    }

    fun list(ctx: Context): List<Project> =
        root(ctx).listFiles { f: File -> f.isDirectory }
            ?.map { Project(it).apply { loadKeyframes() } }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun byId(ctx: Context, id: String): Project? {
        val d = File(root(ctx), id)
        return if (d.isDirectory) Project(d).apply { loadKeyframes() } else null
    }
}
