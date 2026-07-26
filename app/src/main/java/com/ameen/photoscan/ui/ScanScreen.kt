package com.ameen.photoscan.ui

import android.app.Activity
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ameen.photoscan.ar.ArEngine
import com.ameen.photoscan.ar.ArRenderer
import com.ameen.photoscan.ar.RoiSize
import com.ameen.photoscan.data.Project
import com.ameen.photoscan.data.ProjectStore
import com.google.ar.core.ArCoreApk

private const val MIN_SHOTS_TO_BUILD = 12

@Composable
fun ScanScreen(
    onFinished: (Project) -> Unit,
    onCancel: () -> Unit
) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = remember { ArEngine(ctx) }
    val project = remember { ProjectStore.create(ctx) }
    var error by remember { mutableStateOf<String?>(null) }
    var sizeIdx by remember { mutableStateOf(1) }
    var installRequested by remember { mutableStateOf(false) }
    var sessionStarted by remember { mutableStateOf(false) }

    val renderer = remember { ArRenderer(engine) {} }
    val glView = remember {
        GLSurfaceView(ctx).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    LaunchedEffect(Unit) { view.keepScreenOn = true }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                                installRequested = true
                                return@LifecycleEventObserver
                            }
                            else -> Unit
                        }
                        if (engine.session == null) {
                            engine.createSession()
                            engine.beginProject(project)
                            renderer.resetCloud()
                        }
                        @Suppress("DEPRECATION")
                        renderer.displayRotation = activity.windowManager.defaultDisplay.rotation
                        renderer.markGeometryDirty()
                        engine.session?.resume()
                        glView.onResume()
                        sessionStarted = true
                        error = null
                    } catch (t: Throwable) {
                        error = t.message ?: "This device could not start an AR session."
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (sessionStarted) {
                        glView.onPause()
                        engine.session?.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            engine.release()
        }
    }

    val s = engine.state

    Box(Modifier.fillMaxSize().background(Ink.Base)) {

        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        // Legibility scrims, top and bottom only.
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Brush.verticalGradient(listOf(Ink.Base.copy(alpha = 0.78f), Color.Transparent)))
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.Base.copy(alpha = 0.88f))))
        )

        // ---- top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                text = if (s.tracking) "TRACKING" else "SEARCHING",
                color = if (s.tracking) Ink.Signal else Ink.Coral,
                dim = !s.tracking
            )
            StatusChip(
                text = "${fmt(s.pointCount)} PTS",
                color = Ink.Violet,
                dim = true
            )
            Text(
                "CANCEL",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.TextDim,
                modifier = Modifier
                    .padding(8.dp)
                    .clickableNoRipple { project.delete(); onCancel() }
            )
        }

        // ---- bottom controls ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = guidance(s, engine),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !s.tracking -> Ink.Coral
                    s.tooFast -> Ink.Amber
                    else -> Ink.Text
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
            Spacer(Modifier.height(14.dp))

            if (!s.roiLocked) {
                SegmentedRow(
                    options = listOf("30 CM", "60 CM", "1.2 M"),
                    selectedIndex = sizeIdx,
                    onSelect = {
                        sizeIdx = it
                        engine.roiSize = RoiSize.entries[it]
                    }
                )
                Spacer(Modifier.height(14.dp))
            }

            CaptureDial(
                shots = s.shots,
                armProgress = s.armProgress,
                coverage = s.coverage,
                coverageVersion = s.coverageVersion,
                tracking = s.tracking,
                tooFast = s.tooFast,
                firedToggle = s.justFired
            )

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(
                    label = if (s.shots >= MIN_SHOTS_TO_BUILD) "BUILD MODEL"
                    else "NEED ${MIN_SHOTS_TO_BUILD - s.shots} MORE",
                    filled = s.shots >= MIN_SHOTS_TO_BUILD,
                    enabled = s.shots >= MIN_SHOTS_TO_BUILD
                ) {
                    engine.finish()?.let(onFinished)
                }
            }

            if (!s.depthAvailable) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "This device has no depth support, so only shots and poses will be saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Amber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        error?.let { msg ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Ink.Base.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("AR could not start", style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
                    Spacer(Modifier.height(10.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = Ink.TextDim, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                    PillButton("GO BACK", onClick = onCancel)
                }
            }
        }
    }
}

private fun guidance(s: com.ameen.photoscan.ar.CaptureState, engine: ArEngine): String = when {
    !s.tracking -> s.failureReason ?: "Finding your position"
    s.shots == 0 -> "Fit the box over the object — the first shot pins it in place"
    s.tooFast -> "Slow down so the shots stay sharp"
    s.shots < 20 -> "Circle it with side-steps — turning in place doesn't count"
    else -> "${engine.coveragePercent()}% covered · fill the gaps in the ring"
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
