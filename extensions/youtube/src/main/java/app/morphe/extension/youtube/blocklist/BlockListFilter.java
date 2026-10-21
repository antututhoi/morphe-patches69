/*
 * Hides feed items of blocked channels, and livestreams of "livestream blocked" channels.
 */
package app.morphe.extension.youtube.blocklist;

import java.util.List;
import java.util.regex.Pattern;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;

@SuppressWarnings("unused")
public final class BlockListFilter extends Filter {

    private final ByteArrayFilterGroup liveBuffer = new ByteArrayFilterGroup(
            null, "yt_outline_live_black", "yt_outline_experimental_live_black");

    private String cachedKeywords = null;
    private Pattern cachedPattern = null;

    /** Word match on the item's accessibility text: live now, upcoming/scheduled, or ended streams ("Streamed 3 days ago"). */
    private boolean looksLikeLivestream(String accessibility) {
        if (accessibility == null || accessibility.isEmpty()) return false;
        String kw = app.morphe.extension.youtube.settings.Settings.BLOCK_LIVE_KEYWORDS.get();
        if (!kw.equals(cachedKeywords)) {
            StringBuilder sb = new StringBuilder();
            for (String k : kw.split(",")) {
                k = k.trim();
                if (k.isEmpty()) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(Pattern.quote(k));
            }
            cachedPattern = sb.length() == 0 ? null
                    : Pattern.compile("(?<![\\p{L}\\p{N}])(" + sb + ")(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            cachedKeywords = kw;
        }
        return cachedPattern != null && cachedPattern.matcher(accessibility).find();
    }

    public BlockListFilter() {
        // Filters are created at app start: apply picker setting and pull the shared list.
        app.morphe.extension.shared.settings.preference.MiXplorerPicker.enabled =
                app.morphe.extension.youtube.settings.Settings.USE_MIXPLORER_PICKER.get();
        BlockListFileSync.syncSoon();
        addPathCallbacks(new StringFilterGroup(null,
                "video_lockup_with_attachment.e",
                "compact_video.e",
                "video_card.e",
                "video_with_context"));
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              CharSequence path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (!BlockList.isPatchIncluded() || buffer == null || !Utils.endsWith(path, "|CellType|")) {
            return false;
        }
        List<BlockList.Target> channels = BlockList.getChannels();
        if (!channels.isEmpty() && matches(channels, accessibility, buffer)) return true;

        List<BlockList.Target> live = BlockList.getLive();
        return !live.isEmpty() && (liveBuffer.check(buffer).isFiltered() || looksLikeLivestream(accessibility))
                && matches(live, accessibility, buffer);
    }

    private static boolean matches(List<BlockList.Target> targets, String accessibility, byte[] buffer) {
        String acc = accessibility == null ? "" : accessibility.toLowerCase(Locale.ROOT);
        for (BlockList.Target t : targets) {
            if (t.cidBytes != null && FlyoutUtils.byteIndexOf(buffer, t.cidBytes) >= 0) return true;
            if (t.nameBytes != null) {
                if (FlyoutUtils.byteIndexOf(buffer, t.nameBytes) >= 0) return true;
                if (!acc.isEmpty() && acc.contains(t.nameLower)) return true;
            }
        }
        return false;
    }
}
