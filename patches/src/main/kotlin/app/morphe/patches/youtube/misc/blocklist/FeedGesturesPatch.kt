package app.morphe.patches.youtube.misc.blocklist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/gestures/FeedGestures;"

internal object FeedGesturesComponentHostTouchFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/litho/ComponentHost;",
    name = "dispatchTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;")
)

@Suppress("unused")
val feedGesturesPatch = bytecodePatch(
    name = "69-Feed gestures",
    description = "Long-press a video or post to open its 3-dot menu. " +
        "Swipe a video left to hide it, right to block its channel."
) {
    dependsOn(sharedExtensionPatch, settingsPatch, playerTypeHookPatch, blockListPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_feed_long_press_menu", summary = true),
            SwitchPreference("morphe_feed_swipe_gestures", summary = true)
        )

        // Verify the stubbed class is not obfuscated.
        classDefBy("Lcom/facebook/litho/ComponentHost;")

        FeedGesturesComponentHostTouchFingerprint.method.addInstruction(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS->" +
                "onComponentHostTouch(Lcom/facebook/litho/ComponentHost;Landroid/view/MotionEvent;)V"
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
