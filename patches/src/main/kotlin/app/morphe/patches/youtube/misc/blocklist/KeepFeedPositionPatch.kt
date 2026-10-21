package app.morphe.patches.youtube.misc.blocklist

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/FeedPositionGuard;"

@Suppress("unused")
val keepFeedPositionPatch = bytecodePatch(
    name = "69-Keep feed position",
    description = "Stops the home feed from jumping to the top after switching apps, screen off or picture-in-picture."
) {
    dependsOn(sharedExtensionPatch, settingsPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_keep_feed_position", summary = true)
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Activity;)V"
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
