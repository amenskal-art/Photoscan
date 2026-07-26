# PhotoScan

A 3D scanner for Android. Point it at an object, walk around it, get a coloured mesh you can spin, measure and export.

Kotlin and Jetpack Compose throughout. No NDK, no C++, no CUDA — the reconstruction runs on the phone's own GPU through OpenGL ES 3.1 compute shaders.

---

## Getting it building

1. Create an empty GitHub repo.
2. Drag this whole folder onto `github.com/<you>/<repo>/upload/main`. The web uploader keeps folder structure, so `.github/workflows/android.yml` lands in the right place.
3. Commit. The Actions run starts immediately.
4. When it finishes, open the run and download the **photoscan-debug** artifact. Unzip, install the APK.

There is no `gradlew` in the repo on purpose — the workflow installs Gradle 8.10.2 directly, so there is no binary wrapper jar to upload through the web UI.

Requirements on device: Android 8.0+, ARCore installed (the app prompts), and a GPU that supports GLES 3.1 compute. Any phone from roughly 2018 on qualifies.

---

## Offline

The app declares no `INTERNET` permission. Capture, fusion, meshing and export all run on the device, and there is no analytics, crash reporting or model download anywhere in it. Put the phone in airplane mode and every feature still works.

The one dependency that is not inside the APK is **Google Play Services for AR** (ARCore), which supplies the pose tracking and depth. Once it is installed, it also runs entirely on device — no network at session time. But installing it the first time goes through the Play Store, which does need a connection.

For offline distribution, sideload the ARCore APK alongside this one:

```
adb install com.google.ar.core.apk
adb install app-debug.apk
```

Check what a device already has with `adb shell pm list packages | grep com.google.ar.core`. If it comes back empty, ARCore is missing and the app will show the install prompt instead of the camera.

---

## How the capture works

Shots are not taken on a timer. `ArEngine` watches the ARCore pose every frame and fires only when the camera has moved **45 mm** or rotated **7°** since the last shot, and only when angular velocity is below 40°/s so the frame is not motion blurred.

That threshold is what the dial on screen is showing. The inner arc fills as you move and completes at the exact moment the shutter fires. The outer ticks are 36 azimuth bins around the subject — a gap in the ring is a direction the object has never been seen from, which is a hole in the finished mesh.

Each shot stores a JPEG, an ARCore raw depth map, its confidence map, and the 4×4 camera pose. Because ARCore fuses camera and IMU, those poses are metrically scaled and gravity aligned. There is no structure-from-motion step anywhere in this app — no feature matching, no bundle adjustment, no Ceres. The poses are already known, which is what removes about 80% of a classical photogrammetry pipeline.

## How the reconstruction works

Everything below runs on a headless EGL context on a background thread.

1. **Bounds** — subsample every depth map, unproject to world, take a 2–98 percentile box. Trimming stops a few stray far readings from stretching the voxel grid.
2. **Fusion** (`Tsdf.kt`) — a dense truncated signed distance field, 128³ / 192³ / 256³ depending on quality. One compute invocation per voxel per keyframe. Because each invocation owns exactly one voxel there are no atomics and no races, which is what makes 150 frames fuse in seconds. Colour is averaged into a parallel volume with the same weights. Depth texels whose local gradient is too steep are discarded, which kills the flying-pixel skirts you normally get at silhouettes.
3. **Meshing** (`Mesher.kt`) — marching **tetrahedra**, not cubes. Each cube splits into six tets around its main diagonal; each tet has only three topological cases; the result is watertight by construction with no 256-entry lookup table and no ambiguous-face problem. Triangle winding is fixed by comparing the geometric normal against the distance-field gradient rather than by a table. The volume is meshed in 16-voxel Z slabs so peak memory stays bounded at any resolution.
4. **Welding** — the soup is merged into an indexed mesh through a primitive open-addressed hash map, typically removing five sixths of the vertices.
5. **Floater removal** — union-find over the connected components; anything under 2% of the largest component's triangle count is dropped.

Export is GLB, binary PLY, or OBJ. All in metres, at real-world scale, with colour per vertex.

---

## Tuning

| What | Where |
|---|---|
| Shutter thresholds, max shots | `ArEngine.TRANS_THRESHOLD`, `ROT_THRESHOLD`, `MAX_KEYFRAMES` |
| Depth range and confidence floor | `Pipeline.MIN_DEPTH` / `MAX_DEPTH` / `MIN_CONF` |
| Voxel resolutions | `Quality` enum in `data/Project.kt` |
| Truncation band | `mu` in `Pipeline.run` — currently 3.2 voxels |
| Per-slab triangle budget | `Mesher.MAX_TRIS_PER_SLAB` |

---

## What this is honestly not

**Detail is capped by ARCore's depth resolution**, which is typically 160×120 on the depth-from-motion path. Fusing 100+ frames recovers a lot of that back, but this will not resolve a 1 mm feature. For anything where that matters — prosthetic sockets, metrology — this app is the right capture front end and the wrong reconstruction back end. Ship the keyframe folder to a real MVS on a server and use this only for capture and guidance.

**Colour is per-vertex, not a UV texture atlas.** At 2–3 mm voxels it reads well and every viewer supports it, but it is not the same thing as a projected 4K texture. A proper atlas is the single biggest visual upgrade available here.

**Shiny, transparent and untextured surfaces fail**, because ARCore's depth estimation fails on them. Same limitation every phone scanner has.

**Devices without depth support** fall back to capture-only. Shots and poses are still written, so the folder is still usable elsewhere, but no mesh is built on device.

---

## Worth doing next

- **Texture atlas.** Pack keyframes into an atlas with per-face best-view selection. Biggest quality-per-effort win by a wide margin.
- **Poisson or bilateral smoothing** on the TSDF before meshing, to cut depth noise without eating detail.
- **Live meshing.** The fusion shader is fast enough to run during capture; you could show the surface growing instead of just points.
- **Quadric decimation** before export, for models that need to be lighter.
