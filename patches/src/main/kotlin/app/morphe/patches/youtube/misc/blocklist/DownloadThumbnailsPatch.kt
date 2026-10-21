package app.morphe.patches.youtube.misc.blocklist

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/download/ThumbnailDownloader;"

@Suppress("unused")
val downloadThumbnailsPatch = bytecodePatch(
    name = "69-Download thumbnails and post images",
    description = "Adds \"Download thumbnails\" to the video flyout menu. Saves to Download/<channel name>/<title>_<yymmdd>.jpg " +
        "and posts a notification with the error log if a download fails."
) {
    dependsOn(settingsPatch, flyoutPatch, blockListPatch, feedGesturesPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_download_thumbnails_flyout", summary = true),
            SwitchPreference("morphe_download_post_images_flyout", summary = true)
        )
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
