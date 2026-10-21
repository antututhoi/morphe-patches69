package app.morphe.patches.youtube.misc.blocklist

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/blocklist/BlockList;"
private const val FILTER_CLASS = "Lapp/morphe/extension/youtube/blocklist/BlockListFilter;"
private const val PREFERENCE_CLASS = "app.morphe.extension.youtube.settings.preference.BlockListPreference"

@Suppress("unused")
val blockListPatch = bytecodePatch(
    name = "69-Block channels",
    description = "Adds \"Block channel\" and \"Block channel livestreams\" to the video flyout menu, " +
        "hides them from the feed, syncs the list with a multitool-sync.json file " +
        "(Multitool Firefox add-on) and can use MiXplorer as file picker."
) {
    dependsOn(settingsPatch, flyoutPatch, lithoFilterPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            NonInteractivePreference(
                key = "morphe_block_list",
                tag = PREFERENCE_CLASS,
                selectable = true
            ),
            SwitchPreference("morphe_block_list_flyout_menu", summary = true)
        )

        addLithoFilter(FILTER_CLASS)
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
