package com.opencapture.openpocketcine.media

import com.opencapture.opencine.cameracontrols.MediaOperatorCopy
import com.opencapture.opencine.dji.DjiAndroidMediaAssets
import com.opencapture.opencine.vocabulary.MediaFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavior-audit regressions of the Pocket Android media browser against the pre-Core app. */
class PocketMediaRegressionTest {
    private val clip = MediaFile("DCIM/DJI_001/DJI_20261004120000_0001_D.MP4", "MISC/THM/DJI_001/a.scr",
        handle = 0x40100040L, sizeBytes = 4)

    /** PAB-M-37/50/59: a live clip with its own camera handle can be deleted from the app again. */
    @Test fun ownHandleClipIsDeletable() {
        assertEquals(true, DjiAndroidMediaAssets.asset(clip).deletable)
        assertEquals(false, DjiAndroidMediaAssets.asset(clip.copy(handleShared = true)).deletable)
    }

    /** PAB-M-54: Full Resolution Caching pulls the original while a live clip plays. */
    @Test fun playbackCachesTheOriginalWhileLive() {
        assertNotNull(playbackOriginalCache(isLive = true) {})
        assertNull(playbackOriginalCache(isLive = false) {})
    }

    /** PIB-083 (Android twin), PAB-M-14: entry failure names playback; the camera's own gallery is named. */
    @Test fun galleryCopyNamesPlaybackAndTheCameraGallery() {
        assertEquals(MediaOperatorCopy.PLAYBACK_FAILED, pocketMediaLibraryCopy.openFailed)
        assertEquals(MediaOperatorCopy.BROWSING_ON_CAMERA, pocketMediaLibraryCopy.sourceHeld)
        assertEquals(MediaOperatorCopy.NO_CLIPS, pocketMediaLibraryCopy.empty)
    }

    /** IA-552: the provider names the share copies of the no-backup cache. */
    @Test fun providerPathsCoverTheShareCopies() {
        val paths = File("src/main/res/xml/opc_media_file_paths.xml").readText()
        assertTrue(Regex("""<cache-path\s+name="[^"]+"\s+path="share/ready/"\s*/>""").containsMatchIn(paths))
    }
}
