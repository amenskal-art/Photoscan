package com.ameen.photoscan.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ameen.photoscan.data.Project
import com.ameen.photoscan.data.Quality
import com.ameen.photoscan.recon.Pipeline
import com.ameen.photoscan.recon.ReconProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProcessScreen(
    project: Project,
    quality: Quality,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    var stage by remember { mutableStateOf("Starting") }
    var fraction by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current

    LaunchedEffect(project.id, quality) {
        view.keepScreenOn = true
        withContext(Dispatchers.Default) {
            Pipeline.run(project, quality) { p ->
                when (p) {
                    is ReconProgress.Stage -> { stage = p.label; fraction = p.fraction }
                    is ReconProgress.Done -> {
                        fraction = 1f
                        summary = "${fmt(p.mesh.triangleCount)} triangles in ${"%.1f".format(p.seconds)} s"
                    }
                    is ReconProgress.Failed -> failure = p.message
                }
            }
        }
        view.keepScreenOn = false
        if (failure == null) onDone()
    }

    val animated by animateFloatAsState(fraction, tween(220), label = "progress")

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Base),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (failure == null) {
                Text("BUILDING", style = MaterialTheme.typography.labelMedium, color = Ink.Signal)
                Spacer(Modifier.height(14.dp))
                Text(
                    stage,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink.Text,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(26.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                ) { progressBar(animated, size.height) }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${(animated * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.TextDim
                    )
                    Text(
                        "${quality.label.uppercase()} · ${quality.gridSize}³",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.TextDim
                    )
                }
                summary?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Signal)
                }
            } else {
                Text("COULD NOT BUILD", style = MaterialTheme.typography.labelMedium, color = Ink.Coral)
                Spacer(Modifier.height(14.dp))
                Text(
                    failure!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Text,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
                PillButton("BACK TO SCANS", onClick = onBack)
            }
        }
    }
}
