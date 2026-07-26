package com.ameen.photoscan.ar

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ameen.photoscan.data.Keyframe
import com.ameen.photoscan.data.Project
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/** Edge length choices for the scan volume. Everything outside the box is ignored. */
enum class RoiSize(val label: String, val half: Float) {
    SMALL("30 cm", 0.15f),
    MEDIUM("60 cm", 0.30f),
    LARGE("1.2 m", 0.60f)
}

/** What the capture HUD needs to know, updated once per rendered frame. */
class CaptureState {
    var tracking by mutableStateOf(false)
        internal set
    var failureReason by mutableStateOf<String?>(null)
        internal set
    var depthAvailable by mutableStateOf(true)
        internal set
    var shots by mutableIntStateOf(0)
        internal set

    /** 0..1 — how close the current pose is to firing the next shot. Drives the shutter arc. */
    var armProgress by mutableFloatStateOf(0f)
        internal set
    var tooFast by mutableStateOf(false)
        internal set
    var justFired by mutableStateOf(false)
        internal set
    var pointCount by mutableIntStateOf(0)
        internal set

    /** True once the scan box has been anchored in the world by the first shot. */
    var roiLocked by mutableStateOf(false)
        internal set

    /** 36 azimuth bins around the subject, true once seen from that direction. */
    val coverage = BooleanArray(36)
    var coverageVersion by mutableIntStateOf(0)
        internal set
}

/**
 * Drives ARCore and decides when to take a shot.
 *
 * Nothing is captured continuously, and rotating in place never fires the shutter: rotation carries
 * no parallax, so a frame only becomes a keyframe when the camera has *travelled* — either
 * [TRANS_THRESHOLD] outright, or [ROT_THRESHOLD] of rotation combined with at least
 * [MIN_TRANS_FOR_ROT] of movement — and only when the device is steady enough to be sharp.
 *
 * The scan volume: the user aims a box (floating 45 cm ahead) at the subject. The first shot anchors
 * it in world space at the median of the depth it sees. From then on the preview cloud, the coverage
 * ring and the reconstruction all live strictly inside that box.
 */
class ArEngine(private val context: Context) {

    companion object {
        const val TRANS_THRESHOLD = 0.045f          // metres of camera travel between shots
        const val ROT_THRESHOLD = 10.0f             // degrees — only counts alongside real travel
        const val MIN_TRANS_FOR_ROT = 0.020f        // metres of travel needed before rotation counts
        const val MAX_ANGULAR_SPEED = 40.0f         // deg/s above which frames are motion blurred
        const val MIN_INTERVAL_MS = 180L
        const val MAX_KEYFRAMES = 160
        const val PREVIEW_POINT_BUDGET = 220_000
        private const val PREVIEW_POINTS_PER_FRAME = 1600
        private const val AIM_DISTANCE = 0.45f      // where the unanchored box floats, metres
    }

    val state = CaptureState()

    /** Chosen on the scan screen; frozen into [lockedHalf] when the box anchors. */
    var roiSize = RoiSize.MEDIUM

    /** World-space centre of the anchored box. Valid when state.roiLocked. */
    val roiCenter = FloatArray(3)

    /** Follows the camera until the box anchors; what the renderer draws pre-lock. */
    val roiPreviewCenter = FloatArray(3)

    private var lockedHalf = RoiSize.MEDIUM.half
    fun roiHalfNow(): Float = if (state.roiLocked) lockedHalf else roiSize.half

    var session: Session? = null
        private set
    private var project: Project? = null
    private val keyframes = ArrayList<Keyframe>()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- pose bookkeeping -------------------------------------------------
    private val lastKeyPos = FloatArray(3)
    private val lastKeyQuat = FloatArray(4)
    private var hasKey = false
    private var lastFrameQuat: FloatArray? = null
    private var lastFrameTimeNs = 0L
    private var lastShotMs = 0L
    private var angularSpeed = 0f
    private val forwardLocal = floatArrayOf(0f, 0f, -1f)
    private val forwardWorld = FloatArray(3)

    // --- live preview cloud ----------------------------------------------
    /** Interleaved x,y,z,r,g,b in world space. Read by the renderer on the same GL thread. */
    val previewPoints = FloatArray(PREVIEW_POINT_BUDGET * 6)
    @Volatile var previewCount = 0
        private set

    private val scratchView = FloatArray(16)
    private val scratchModel = FloatArray(16)

    fun createSession(): Session {
        val s = Session(context)
        val cfg = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = when {
                s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                else -> Config.DepthMode.DISABLED
            }
        }
        s.configure(cfg)
        state.depthAvailable = cfg.depthMode != Config.DepthMode.DISABLED
        session = s
        return s
    }

    fun beginProject(p: Project) {
        project = p
        keyframes.clear()
        hasKey = false
        previewCount = 0
        state.shots = 0
        state.pointCount = 0
        state.roiLocked = false
        lockedHalf = roiSize.half
        state.coverage.fill(false)
        state.coverageVersion++
    }

    fun finish(): Project? {
        val p = project ?: return null
        if (state.roiLocked) {
            p.roiCenter = roiCenter.copyOf()
            p.roiHalf = lockedHalf
        }
        p.saveMeta(keyframes)
        return p
    }

    fun keyframeList(): List<Keyframe> = keyframes.toList()

    fun release() {
        io.cancel()
        session?.close()
        session = null
    }

    // ---------------------------------------------------------------------
    // Called once per rendered frame, on the GL thread, right after session.update().
    // ---------------------------------------------------------------------
    fun onFrame(frame: Frame) {
        val cam: Camera = frame.camera
        val ts = cam.trackingState == TrackingState.TRACKING
        state.tracking = ts
        state.failureReason = if (ts) null else when (cam.trackingFailureReason) {
            TrackingFailureReason.EXCESSIVE_MOTION -> "Slow down"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at more texture"
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
            else -> "Finding your position"
        }
        if (!ts) { state.armProgress = 0f; return }

        val pose = cam.pose
        val pos = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
        val quat = pose.rotationQuaternion

        // Keep the aiming box floating ahead of the camera until it anchors.
        if (!state.roiLocked) {
            pose.rotateVector(forwardLocal, 0, forwardWorld, 0)
            roiPreviewCenter[0] = pos[0] + forwardWorld[0] * AIM_DISTANCE
            roiPreviewCenter[1] = pos[1] + forwardWorld[1] * AIM_DISTANCE
            roiPreviewCenter[2] = pos[2] + forwardWorld[2] * AIM_DISTANCE
        }

        // Angular speed drives the blur guard.
        val prevQ = lastFrameQuat
        val dtNs = frame.timestamp - lastFrameTimeNs
        if (prevQ != null && dtNs in 1..500_000_000L) {
            val deg = quatAngleDeg(prevQ, quat)
            val dt = dtNs / 1e9f
            angularSpeed = angularSpeed * 0.6f + (deg / dt) * 0.4f
        }
        lastFrameQuat = quat.copyOf()
        lastFrameTimeNs = frame.timestamp
        state.tooFast = angularSpeed > MAX_ANGULAR_SPEED

        if (!hasKey) {
            state.armProgress = 1f
            tryCapture(frame, cam, pos, quat)
            return
        }

        val dTrans = dist(pos, lastKeyPos)
        val dRot = quatAngleDeg(lastKeyQuat, quat)

        // Translation is the real trigger. Rotation only helps once you have actually moved,
        // so turning in place shows no progress and fires nothing.
        val transP = dTrans / TRANS_THRESHOLD
        val rotP = if (dTrans >= MIN_TRANS_FOR_ROT) dRot / ROT_THRESHOLD else 0f
        val progress = maxOf(transP, rotP).coerceIn(0f, 1f)
        state.armProgress = progress

        val now = System.currentTimeMillis()
        val ready = progress >= 1f &&
                !state.tooFast &&
                now - lastShotMs >= MIN_INTERVAL_MS &&
                keyframes.size < MAX_KEYFRAMES

        if (ready) tryCapture(frame, cam, pos, quat)
    }

    private fun tryCapture(frame: Frame, cam: Camera, pos: FloatArray, quat: FloatArray) {
        val proj = project ?: return

        var depthImage: Image? = null
        var confImage: Image? = null
        var rgbImage: Image? = null
        try {
            depthImage = try {
                frame.acquireRawDepthImage16Bits()
            } catch (e: Throwable) {
                try { frame.acquireDepthImage16Bits() } catch (e2: Throwable) { null }
            } ?: return

            confImage = try { frame.acquireRawDepthConfidenceImage() } catch (e: Throwable) { null }
            rgbImage = try { frame.acquireCameraImage() } catch (e: NotYetAvailableException) { null } ?: return

            val dw = depthImage.width
            val dh = depthImage.height
            val depth = readDepth(depthImage, dw, dh)
            val conf = readConfidence(confImage, dw, dh)
            val jpeg = yuvToJpeg(rgbImage, 88)

            // Intrinsics are reported for the CPU image; rescale into depth pixels.
            val intr = cam.imageIntrinsics
            val dims = intr.imageDimensions
            val sx = dw.toFloat() / dims[0]
            val sy = dh.toFloat() / dims[1]
            val fl = intr.focalLength
            val pp = intr.principalPoint
            val fx = fl[0] * sx; val fy = fl[1] * sy
            val cxp = pp[0] * sx; val cyp = pp[1] * sy

            cam.getViewMatrix(scratchView, 0)
            cam.pose.toMatrix(scratchModel, 0)

            // The first shot anchors the scan box at the median of the depth it sees.
            if (!state.roiLocked) {
                lockRoi(depth, conf, dw, dh, fx, fy, cxp, cyp, scratchModel)
            }

            val kf = Keyframe(
                index = keyframes.size,
                timestampNs = frame.timestamp,
                viewMatrix = scratchView.copyOf(),
                camPosition = pos.copyOf(),
                fx = fx, fy = fy, cx = cxp, cy = cyp,
                depthWidth = dw, depthHeight = dh
            )
            keyframes.add(kf)

            addPreviewPoints(depth, conf, kf, scratchModel.copyOf())
            updateCoverage(pos)

            io.launch { writeKeyframe(proj, kf, jpeg, depth, conf) }

            System.arraycopy(pos, 0, lastKeyPos, 0, 3)
            System.arraycopy(quat, 0, lastKeyQuat, 0, 4)
            hasKey = true
            lastShotMs = System.currentTimeMillis()
            state.shots = keyframes.size
            state.armProgress = 0f
            state.justFired = !state.justFired
        } catch (t: Throwable) {
            // A dropped frame is never fatal — the next pose change will try again.
        } finally {
            depthImage?.close(); confImage?.close(); rgbImage?.close()
        }
    }

    /** Anchor the box: median world point of the central half of this frame's confident depth. */
    private fun lockRoi(
        depth: ShortArray, conf: ByteArray, dw: Int, dh: Int,
        fx: Float, fy: Float, cx: Float, cy: Float, model: FloatArray
    ) {
        val xs = ArrayList<Float>(700); val ys = ArrayList<Float>(700); val zs = ArrayList<Float>(700)
        val v = FloatArray(4); val o = FloatArray(4)
        val x0 = dw / 4; val x1 = dw * 3 / 4
        val y0 = dh / 4; val y1 = dh * 3 / 4
        val stepX = maxOf(1, (x1 - x0) / 26)
        val stepY = maxOf(1, (y1 - y0) / 26)
        var py = y0
        while (py < y1) {
            var px = x0
            while (px < x1) {
                val k = py * dw + px
                if (k < depth.size) {
                    val raw = depth[k].toInt() and 0xFFFF
                    val c = if (k < conf.size) conf[k].toInt() and 0xFF else 0
                    if (raw != 0 && c > 60) {
                        val d = raw * 0.001f
                        if (d in 0.15f..2.5f) {
                            v[0] = (px - cx) / fx * d
                            v[1] = -((py - cy) / fy * d)
                            v[2] = -d
                            v[3] = 1f
                            Matrix.multiplyMV(o, 0, model, 0, v, 0)
                            xs.add(o[0]); ys.add(o[1]); zs.add(o[2])
                        }
                    }
                }
                px += stepX
            }
            py += stepY
        }

        if (xs.size >= 30) {
            xs.sort(); ys.sort(); zs.sort()
            roiCenter[0] = xs[xs.size / 2]
            roiCenter[1] = ys[ys.size / 2]
            roiCenter[2] = zs[zs.size / 2]
        } else {
            System.arraycopy(roiPreviewCenter, 0, roiCenter, 0, 3)
        }
        lockedHalf = roiSize.half
        state.roiLocked = true
    }

    // ---------------------------------------------------------------------
    // Image plumbing
    // ---------------------------------------------------------------------

    private fun readDepth(img: Image, w: Int, h: Int): ShortArray {
        val plane = img.planes[0]
        val buf: ShortBuffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val rowStrideShorts = plane.rowStride / 2
        val out = ShortArray(w * h)
        for (y in 0 until h) {
            val src = y * rowStrideShorts
            if (src + w > buf.limit()) break
            buf.position(src)
            buf.get(out, y * w, w)
        }
        return out
    }

    private fun readConfidence(img: Image?, w: Int, h: Int): ByteArray {
        if (img == null) return ByteArray(w * h) { 255.toByte() }
        val plane = img.planes[0]
        val buf: ByteBuffer = plane.buffer
        val stride = plane.rowStride
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val src = y * stride
            if (src + w > buf.limit()) break
            buf.position(src)
            buf.get(out, y * w, w)
        }
        return out
    }

    private fun yuvToJpeg(image: Image, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yStride = yPlane.rowStride
        var o = 0
        for (row in 0 until h) {
            yBuf.position(row * yStride)
            yBuf.get(nv21, o, w)
            o += w
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride
        val cw = w / 2
        val ch = h / 2
        for (row in 0 until ch) {
            var uPos = row * uvRow
            var vPos = row * uvRow
            for (col in 0 until cw) {
                if (vPos < vBuf.limit()) nv21[o++] = vBuf.get(vPos)
                if (uPos < uBuf.limit()) nv21[o++] = uBuf.get(uPos)
                uPos += uvPix
                vPos += uvPix
            }
        }

        val out = ByteArrayOutputStream(64 * 1024)
        YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun writeKeyframe(p: Project, kf: Keyframe, jpeg: ByteArray, depth: ShortArray, conf: ByteArray) {
        runCatching {
            p.rgbFile(kf.index).writeBytes(jpeg)
            if (kf.index == 0) p.thumbFile.writeBytes(jpeg)
            writeShorts(p.depthFile(kf.index), depth)
            p.confFile(kf.index).writeBytes(conf)
        }
    }

    private fun writeShorts(f: File, data: ShortArray) {
        val bb = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(data)
        DataOutputStream(f.outputStream().buffered()).use { it.write(bb.array()) }
    }

    // ---------------------------------------------------------------------
    // Live feedback
    // ---------------------------------------------------------------------

    private fun addPreviewPoints(depth: ShortArray, conf: ByteArray, kf: Keyframe, model: FloatArray) {
        val total = kf.depthWidth * kf.depthHeight
        val stride = maxOf(1, total / PREVIEW_POINTS_PER_FRAME)
        val locked = state.roiLocked
        val bh = lockedHalf * 1.05f
        val confMin = if (locked) 60 else 90
        val v = FloatArray(4)
        val outV = FloatArray(4)
        var i = 0
        var written = previewCount
        while (i < total && written < PREVIEW_POINT_BUDGET) {
            val raw = depth[i].toInt() and 0xFFFF
            if (raw != 0 && (conf[i].toInt() and 0xFF) > confMin) {
                val d = raw * 0.001f
                if (d in 0.15f..4.0f) {
                    val px = i % kf.depthWidth
                    val py = i / kf.depthWidth
                    // Pixel -> camera (OpenCV) -> camera (OpenGL) -> world.
                    v[0] = (px - kf.cx) / kf.fx * d
                    v[1] = -((py - kf.cy) / kf.fy * d)
                    v[2] = -d
                    v[3] = 1f
                    Matrix.multiplyMV(outV, 0, model, 0, v, 0)
                    val inside = !locked ||
                            (abs(outV[0] - roiCenter[0]) <= bh &&
                             abs(outV[1] - roiCenter[1]) <= bh &&
                             abs(outV[2] - roiCenter[2]) <= bh)
                    if (inside) {
                        val b = written * 6
                        previewPoints[b] = outV[0]
                        previewPoints[b + 1] = outV[1]
                        previewPoints[b + 2] = outV[2]
                        // Depth-cued colour so the cloud reads as shape, not noise.
                        val t = ((d - 0.2f) / 1.8f).coerceIn(0f, 1f)
                        previewPoints[b + 3] = 0.30f + 0.55f * (1f - t)
                        previewPoints[b + 4] = 0.88f - 0.25f * t
                        previewPoints[b + 5] = 0.70f + 0.25f * t
                        written++
                    }
                }
            }
            i += stride
        }
        previewCount = written
        state.pointCount = written
    }

    private fun updateCoverage(camPos: FloatArray) {
        if (!state.roiLocked) return
        var a = Math.toDegrees(
            atan2((camPos[2] - roiCenter[2]).toDouble(), (camPos[0] - roiCenter[0]).toDouble())
        ).toFloat()
        if (a < 0) a += 360f
        val bin = ((a / 10f).toInt()).coerceIn(0, 35)
        if (!state.coverage[bin]) {
            state.coverage[bin] = true
            state.coverageVersion++
        }
    }

    fun coveragePercent(): Int {
        val n = state.coverage.count { it }
        return (n * 100 / 36)
    }

    // ---------------------------------------------------------------------

    private fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun quatAngleDeg(a: FloatArray, b: FloatArray): Float {
        var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        d = min(1f, abs(d))
        return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
    }
}
