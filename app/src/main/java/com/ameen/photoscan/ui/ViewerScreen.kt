package com.ameen.photoscan.ui

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.ameen.photoscan.data.Project
import com.ameen.photoscan.data.ProjectStore
import com.ameen.photoscan.io.ExportFormat
import com.ameen.photoscan.io.Exporters
import com.ameen.photoscan.recon.Mesh
import com.ameen.photoscan.viewer.ModelSurfaceView
import com.ameen.photoscan.viewer.ShadeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(project: Project, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var mesh by remember { mutableStateOf<Mesh?>(null) }
    var loading by remember { mutableStateOf(true) }
    var modeIndex by remember { mutableStateOf(1) }
    var showExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }

    var spinning by remember { mutableStateOf(true) }
    val surface = remember { ModelSurfaceView(ctx).apply { onInteract = { spinning = false } } }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(project.id) {
        val m = withContext(Dispatchers.IO) { Mesh.load(project.meshFile) }
        mesh = m
        surface.meshRenderer.mesh = m
        loading = false
    }

    LaunchedEffect(modeIndex) {
        surface.meshRenderer.mode = ShadeMode.entries[modeIndex]
    }

    LaunchedEffect(spinning) {
        surface.meshRenderer.autoSpin = spinning
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
    ) {
        if (mesh != null) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "BACK",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onBack() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
            mesh?.let {
                StatusChip("${fmt(it.triangleCount)} TRI", color = Ink.Signal, dim = true)
            }
            Text(
                "RESET",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { surface.resetView() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        if (loading) {
            Text(
                "Loading model…",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.TextDim,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (mesh == null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No model yet", style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This scan has shots but has not been built.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextDim,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (mesh != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SegmentedRow(
                    options = listOf("CLAY", "COLOUR", "NORMAL", "WIRE"),
                    selectedIndex = modeIndex,
                    onSelect = { modeIndex = it }
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(if (spinning) "STOP SPIN" else "SPIN") { spinning = !spinning }
                    PillButton("EXPORT", filled = true) { showExport = true }
                }
                exporting?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Signal)
                }
            }
        }

        if (showExport) {
            ModalBottomSheet(
                onDismissRequest = { showExport = false },
                sheetState = sheetState,
                containerColor = Ink.Surface
            ) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text("Export", style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Real-world scale, metres. Colour is stored per vertex.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextDim
                    )
                    Spacer(Modifier.height(18.dp))
                    ExportFormat.entries.forEach { fmtOpt ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Ink.SurfaceHi)
                                .clickable {
                                    showExport = false
                                    val m = mesh ?: return@clickable
                                    exporting = "Writing ${fmtOpt.label}…"
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) {
                                            Exporters.export(
                                                m,
                                                ProjectStore.exportsDir(ctx),
                                                project.id,
                                                fmtOpt
                                            )
                                        }
                                        exporting = "Saved ${file.name}"
                                        val uri = FileProvider.getUriForFile(
                                            ctx, "${ctx.packageName}.fileprovider", file
                                        )
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = fmtOpt.mime
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(Intent.createChooser(send, "Share model"))
                                    }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fmtOpt.label, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                                Text(fmtOpt.blurb, style = MaterialTheme.typography.bodySmall, color = Ink.TextDim)
                            }
                            Text("→", style = MaterialTheme.typography.titleMedium, color = Ink.Signal)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
