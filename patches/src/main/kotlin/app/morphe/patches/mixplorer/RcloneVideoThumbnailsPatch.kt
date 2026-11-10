package app.morphe.patches.mixplorer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val COMPATIBILITY_MIXPLORER = Compatibility(
    name = "MiXplorer Silver",
    packageName = "com.mixplorer.silver",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0x2196F3,
    signatures = setOf("124609beb0cf3204401319fa4ce40f3b0bc1a3c3ba689fb9e409cc8b320293a4"),
    targets = listOf(AppTarget(version = "6.71.15-Silver", minSdk = 21, isExperimental = true)),
)

/** MiXplorer's video thumbnail maker (found by the file types and the log tag it uses). */
internal object MixVideoThumbnailFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("L", "I", "I", "Z", "Z"),
    strings = listOf("video/mp4", "mkv", "webm", "MediaHelper", "VIDEO"),
)

/** MiXplorer's "grab a frame" helper (it looks the frame methods up by name). */
internal object MixFrameGrabFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/graphics/Bitmap;",
    strings = listOf("getScaledFrameAtTime", "getFrameAtTime"),
)

private const val OLD_TIMEOUT_MS = 10_000L
private const val NEW_TIMEOUT_MS = 90_000L
private const val OLD_MIN_FRAME_US = 5_000_000L
private const val NEW_MIN_FRAME_US = 1_000_000L
private const val FRAME_AT_US = 3_000_000L

@Suppress("unused")
val rcloneVideoThumbnailsPatch = bytecodePatch(
    name = "69-rclone video thumbnails",
    description = "Lets MiXplorer make video thumbnails (a frame from the video) in slow folders like " +
        "rclone through RSAF: waits up to 90 s instead of 10 s, reads the whole file for every video type, " +
        "and takes the frame 3 s in so less has to be downloaded.",
) {
    compatibleWith(COMPATIBILITY_MIXPLORER)

    execute {
        val thumb = MixVideoThumbnailFingerprint.method
        var timeouts = 0
        var limits = 0
        thumb.instructions.withIndex().toList().forEach { (index, ins) ->
            if (ins !is WideLiteralInstruction || ins !is OneRegisterInstruction) return@forEach
            val reg = ins.registerA
            when {
                // the watchdog that gives up on the thumbnail: only the one handed to Handler.postDelayed
                ins.wideLiteral == OLD_TIMEOUT_MS && feedsPostDelayed(thumb.instructions.drop(index + 1).take(4)) -> {
                    thumb.replaceInstruction(index, "const-wide/32 v$reg, $NEW_TIMEOUT_MS")
                    timeouts++
                }
                // only the first 7 MB / 15 MB of some video types were read: read the whole file
                (ins.wideLiteral == 0x700000L || ins.wideLiteral == 0xf00000L) && ins.opcode == Opcode.CONST_WIDE_32 -> {
                    thumb.replaceInstruction(index, "const-wide v$reg, 0x7fffffffffffffffL")
                    limits++
                }
            }
        }
        if (timeouts == 0) throw PatchException("69: thumbnail timeout not found")

        // take the frame from ~3 s in (not 7-50 s or the middle), so a cloud file needs less data
        val grab = MixFrameGrabFingerprint.method
        grab.instructions.withIndex().toList().forEach { (index, ins) ->
            if (ins is WideLiteralInstruction && ins is OneRegisterInstruction && ins.wideLiteral == OLD_MIN_FRAME_US) {
                grab.replaceInstruction(index, "const-wide/32 v${ins.registerA}, $NEW_MIN_FRAME_US")
            }
        }
        // the wanted frame time is the 3rd parameter (a long, so registers p2 + p3)
        grab.addInstruction(0, "const-wide/32 p2, $FRAME_AT_US")
    }
}

private fun feedsPostDelayed(next: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>) =
    next.any {
        it is ReferenceInstruction &&
            (it.reference as? MethodReference)?.let { r -> r.name == "postDelayed" && r.definingClass == "Landroid/os/Handler;" } == true
    }
