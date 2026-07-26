package com.ameen.photoscan.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ameen.photoscan.data.Project
import com.ameen.photoscan.data.ProjectStore

@Composable
fun HomeScreen(
    onNewScan: () -> Unit,
    onOpen: (Project) -> Unit
) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val projects = remember(refresh) { ProjectStore.list(ctx) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp)
        ) {
            item {
                Column(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 22.dp)
                        .padding(top = 28.dp, bottom = 18.dp)
                ) {
                    Text("PHOTOSCAN", style = MaterialTheme.typography.labelMedium, color = Ink.Signal)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Walk around it.\nThe shutter knows when.",
                        style = MaterialTheme.typography.displaySmall,
                        color = Ink.Text
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Shots are taken on movement, not on a timer, so every frame adds something the last one missed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextDim
                    )
                }
            }

            if (projects.isEmpty()) {
                item { EmptyState() }
            } else {
                item {
                    Text(
                        "SCANS · ${projects.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.TextDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }
                items(projects, key = { it.id }) { p ->
                    ProjectRow(
                        project = p,
                        onClick = { onOpen(p) },
                        onDelete = { p.delete(); refresh++ }
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
        ) {
            PillButton("START A SCAN", filled = true, onClick = onNewScan)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nothing scanned yet", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Put an object on a table with room to walk around it. Good light, plenty of texture on the surface, nothing shiny or transparent.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextDim
        )
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit, onDelete: () -> Unit) {
    val thumb = remember(project.id) {
        project.thumbFile.takeIf { it.exists() }?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
    }
    val done = project.meshFile.exists()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Surface)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink.SurfaceHi),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("—", color = Ink.TextDim, style = MaterialTheme.typography.labelLarge)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
            Text(project.displayDate(), style = MaterialTheme.typography.bodySmall, color = Ink.TextDim)
            Text(
                if (done) "${project.keyframeCount} shots · ${fmt(project.triangleCount)} triangles"
                else "${project.keyframeCount} shots · not built yet",
                style = MaterialTheme.typography.labelSmall,
                color = if (done) Ink.Signal else Ink.Amber
            )
        }

        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(50))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Delete scan",
                tint = Ink.TextDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

fun fmt(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.0fk", n / 1000f)
    else -> n.toString()
}
