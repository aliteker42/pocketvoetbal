package com.opencapture.openpocketcine

import com.opencapture.opencine.appkit.ShellModel
import android.content.Context
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.opencapture.monitorui.ReviewLaunch
import com.opencapture.monitorui.ReviewLiveValues
import com.opencapture.opencine.core.CameraTimecode
import com.opencapture.opencine.core.ConnectedCamera
import com.opencapture.opencine.core.SavedCamera
import com.opencapture.opencine.dji.DjiCameraSessionAdapter
import com.opencapture.opencine.platform.ReviewMediaSeed
import com.opencapture.opencine.platform.ReviewLiveVideoSeed
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.CameraModel
import com.opencapture.opencine.vocabulary.CameraStatus
import com.opencapture.opencine.vocabulary.FoundCamera
import com.opencapture.opencine.vocabulary.MediaFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect

internal fun Modifier.reviewMetadata(review: ReviewLaunch?): Modifier =
    if (review == null) this else semantics { testTagsAsResourceId = true }

/** SIMULATED review through the production shell, cache, catalog, gallery and player. */
internal object MonitorReview {
    /** The screens this host stages; anything else decodes null and runs the real startup. */
    private val screens = setOf(
        ReviewLaunch.Screen.CAMERAS, ReviewLaunch.Screen.PAIR, ReviewLaunch.Screen.LIVE,
        ReviewLaunch.Screen.MEDIA, ReviewLaunch.Screen.PLAYBACK, ReviewLaunch.Screen.SETTINGS,
        ReviewLaunch.Screen.CHROME_EDIT,
    )

    fun decode(intent: Intent?): ReviewLaunch? = ReviewLaunch.decode { intent?.getStringExtra(it) }
        ?.takeIf { it.source == ReviewLaunch.Source.LOCAL && !it.connected && it.screen in screens }

    /**
     * Runs before the model builds its cameras store: every staged screen shows the two saved
     * cameras, idle, under it (the review `state` names a scripted pipeline, none is that page),
     * so no staged cell starts on the first-run pair wizard.
     */
    fun prepare(context: Context, review: ReviewLaunch) {
        // ReviewCameras' two saved cameras as the shipping app stored them; "" is that default.
        ReviewCameras.seed("", context.getSharedPreferences(AppModel.LEGACY_CAMERAS, Context.MODE_PRIVATE))
    }

    /** The review body, a Pocket 4 Pro as the iOS live review stages it. */
    val liveCamera = FoundCamera("simulated-dji", "SIMULATED", "SIMULATED Pocket 4 Pro",
        CameraModel(name = "Osmo Pocket 4 Pro", family = "pocket", verified = true), null)

    /** The shared LIVE review values as this camera reports them, in its own colour (D-Log2). */
    fun liveStatus(values: ReviewLiveValues = ReviewLiveValues.SIMULATED): CameraStatus =
        CameraStatus(batteryPercent = values.batteryPercent, storageTotalMb = values.storageTotalMebibytes.toInt(),
            storageFreeMb = values.storageFreeMebibytes.toInt(), shootingMode = 1, iso = values.iso,
            isoIndex = 7, shutterDenom = values.shutterDenominator, expoMode = CameraCommands.EXPO_MANUAL,
            colorMode = CameraCommands.COLOR_DLOG2, resolutionCode = CameraCommands.RES_4K, fps = values.framesPerSecond,
            fpsIndex = 2, wbMode = 6, wbKelvin = values.whiteBalanceKelvin, focusMode = 2, focusTrack = 0, audioChannel = 2)
            .withTimecodeValue(CameraTimecode(values.timecodeHours, values.timecodeMinutes, values.timecodeSeconds, frames = 0))

    suspend fun start(context: Context, model: AppModel, review: ReviewLaunch) {
        when (review.screen) {
            ReviewLaunch.Screen.PAIR -> model.pairing = true
            // The live review stages values through the real session projection. The editor keeps
            // its idle control seam; neither starts a radio, connection or command to a camera.
            ReviewLaunch.Screen.LIVE, ReviewLaunch.Screen.CHROME_EDIT -> {
                val camera = if (review.screen == ReviewLaunch.Screen.LIVE) {
                    model.session.stageSimulatedLive(liveCamera, liveStatus())
                    SavedCamera(liveCamera.id, DjiCameraSessionAdapter.INTEGRATION,
                        advertisedName = liveCamera.name, model = liveCamera.model.name)
                } else {
                    SavedCamera(STAGED_CAMERA, DjiCameraSessionAdapter.INTEGRATION,
                        advertisedName = "SIMULATED common input", model = "SIMULATED")
                }
                model.shell.present(ConnectedCamera(camera, model.session.control))
                // The chrome editor over the same staged monitor (iOS `beginChromeEditing`).
                if (review.screen == ReviewLaunch.Screen.CHROME_EDIT) model.beginChromeEditing(model.currentDispMode)
                if (review.screen == ReviewLaunch.Screen.LIVE) {
                    val monitor = checkNotNull(model.shell.state.value.monitor)
                    val decoder = model.session.decoder
                    val owner = decoder.claimInputOwner()
                    decoder.advanceInputEpoch(owner, 1)
                    try {
                        withContext(Dispatchers.Default) {
                            ReviewLiveVideoSeed.frames(context).takeWhile {
                                model.shell.state.value.monitor === monitor
                            }.collect { decoder.decode(it, owner, 1) }
                        }
                    } finally {
                        // Retire only this input owner; an old review cannot invalidate a successor.
                        decoder.advanceInputEpoch(owner, 2)
                    }
                }
            }
            ReviewLaunch.Screen.MEDIA, ReviewLaunch.Screen.PLAYBACK -> stageMedia(context, model, review)
            ReviewLaunch.Screen.SETTINGS -> {
                review.settingsTab?.let { model.operatorSettingsTab = it }
                model.shell.open(ShellModel.Cover.SETTINGS)
            }
            else -> Unit // CAMERAS: the seeded saved cameras leave the shell on the cameras page.
        }
    }

    private suspend fun stageMedia(context: Context, model: AppModel, review: ReviewLaunch) {
        val namespace = "SIMULATED-S1"
        withContext(Dispatchers.IO) {
            val cache = model.mediaCache
            val clips = ReviewMediaSeed.write(File(context.cacheDir, "SIMULATED-S1"), review.mediaCount) { clip ->
                val file = file(clip)
                ReviewMediaSeed.Destination(cache.fileCache(namespace, file), cache.thumbnailFile(namespace, file))
            }
            cache.persistCatalog(clips.map(::file), namespace)
            cache.rememberCameraId(namespace)
        }
        model.shell.open(ShellModel.Cover.MEDIA)
    }

    private fun file(clip: ReviewMediaSeed.Clip): MediaFile = MediaFile(
        path = "DCIM/SIMULATED/${clip.filename}", thumbPath = "DCIM/SIMULATED/${clip.filename}",
        sizeBytes = clip.byteCount, durationSeconds = clip.durationSeconds,
        resolution = "${clip.width}x${clip.height}", fps = clip.frameRate,
    )

    private const val STAGED_CAMERA = "simulated-common-input"
}
