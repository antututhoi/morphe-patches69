package app.morphe.patches.youtube.misc.blocklist

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/FullscreenProgressBar;"

@Suppress("unused")
val fullscreenProgressBarPatch = bytecodePatch(
    name = "69-Fullscreen progress bar",
    description = "Always shows a draggable progress bar at the bottom of the video in fullscreen."
) {
    dependsOn(sharedExtensionPatch, settingsPatch, playerTypeHookPatch, videoInformationPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_fullscreen_progress_bar", summary = true)
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Activity;)V"
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
