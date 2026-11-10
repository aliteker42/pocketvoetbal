package com.opencapture.openpocketcine

import androidx.compose.runtime.Composable
import com.opencapture.opencine.appkit.ShellModel
import com.opencapture.openpocketcine.media.MediaLibraryScreen

/** Native page content over navigation and monitor ownership supplied by the shared shell. */
@Composable
internal fun ShellCoverPage(model: AppModel, cover: ShellModel.Cover) {
    when (cover) {
        ShellModel.Cover.SETTINGS -> OperatorSetupScreen(model) { model.shell.dismissCover() }
        ShellModel.Cover.MEDIA -> MediaLibraryScreen(model) { model.shell.dismissCover() }
    }
}
