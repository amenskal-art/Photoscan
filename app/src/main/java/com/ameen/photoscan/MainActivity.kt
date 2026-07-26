package com.ameen.photoscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ameen.photoscan.data.ProjectStore
import com.ameen.photoscan.data.Quality
import com.ameen.photoscan.ui.HomeScreen
import com.ameen.photoscan.ui.Ink
import com.ameen.photoscan.ui.PhotoScanTheme
import com.ameen.photoscan.ui.PillButton
import com.ameen.photoscan.ui.ProcessScreen
import com.ameen.photoscan.ui.ScanScreen
import com.ameen.photoscan.ui.ViewerScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoScanTheme {
                Box(Modifier.fillMaxSize().background(Ink.Base)) {
                    AppNav()
                }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val ctx = LocalContext.current

    var pendingScan by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDenied = false
            nav.navigate("scan")
        } else {
            permissionDenied = true
        }
        pendingScan = false
    }

    LaunchedEffect(pendingScan) {
        if (!pendingScan) return@LaunchedEffect
        val has = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (has) {
            pendingScan = false
            nav.navigate("scan")
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    NavHost(navController = nav, startDestination = "home") {

        composable("home") {
            Box(Modifier.fillMaxSize()) {
                HomeScreen(
                    onNewScan = { pendingScan = true },
                    onOpen = { p ->
                        if (p.meshFile.exists()) nav.navigate("viewer/${p.id}")
                        else nav.navigate("quality/${p.id}")
                    }
                )
                if (permissionDenied) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp, start = 24.dp, end = 24.dp)
                    ) {
                        Text(
                            "Camera access is required to scan. Enable it in Settings, then try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Amber,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        composable("scan") {
            ScanScreen(
                onFinished = { p -> nav.navigate("quality/${p.id}") { popUpTo("home") } },
                onCancel = { nav.popBackStack("home", inclusive = false) }
            )
        }

        composable(
            "quality/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            QualityScreen(
                onPick = { q -> nav.navigate("process/$id/${q.name}") { popUpTo("home") } },
                onBack = { nav.popBackStack("home", inclusive = false) }
            )
        }

        composable(
            "process/{id}/{quality}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("quality") { type = NavType.StringType }
            )
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val q = runCatching { Quality.valueOf(entry.arguments?.getString("quality") ?: "") }
                .getOrDefault(Quality.BALANCED)
            val project = remember(id) { ProjectStore.byId(ctx, id) }
            if (project == null) {
                nav.popBackStack("home", inclusive = false)
            } else {
                ProcessScreen(
                    project = project,
                    quality = q,
                    onDone = { nav.navigate("viewer/$id") { popUpTo("home") } },
                    onBack = { nav.popBackStack("home", inclusive = false) }
                )
            }
        }

        composable(
            "viewer/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val project = remember(id) { ProjectStore.byId(ctx, id) }
            if (project == null) {
                nav.popBackStack("home", inclusive = false)
            } else {
                ViewerScreen(project = project, onBack = { nav.popBackStack("home", inclusive = false) })
            }
        }
    }
}

@Composable
private fun QualityScreen(onPick: (Quality) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
            .padding(horizontal = 24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text("DETAIL", style = MaterialTheme.typography.labelMedium, color = Ink.Signal)
        Spacer(Modifier.height(10.dp))
        Text(
            "How fine should the surface be?",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink.Text
        )
        Spacer(Modifier.height(24.dp))

        Quality.entries.forEach { q ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ink.Surface)
                    .clickable { onPick(q) }
                    .padding(18.dp)
            ) {
                Text(q.label, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                Spacer(Modifier.height(4.dp))
                Text(q.note, style = MaterialTheme.typography.bodySmall, color = Ink.TextDim)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${q.gridSize}³ voxels",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.TextDim
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Box(Modifier.align(Alignment.CenterHorizontally)) {
            PillButton("NOT NOW", onClick = onBack)
        }
    }
}
