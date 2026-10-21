/*
 * Hides feed items of blocked channels, and livestreams of "livestream blocked" channels.
 * Reads the item the same way Morphe's keyword filter does: the item's whole buffer.
 */
package app.morphe.extension.youtube.blocklist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class BlockListFilter extends Filter {

    private static final String[] PARTS_TO_SKIP = {"metadata.e", "thumbnail.e", "avatar.e", "overflow_button.e"};

    private final StringFilterGroup feedItems = new StringFilterGroup(null,
            "home_video_with_context.e",
            "search_video_with_context.e",
            "video_with_context.e",
            "related_video_with_context.e",
            "video_lockup_with_attachment.e",
            "compact_video.e",
            "video_card.e");

    private final StringFilterGroup posts = new StringFilterGroup(null,
            "images_post_responsive_root.e",
            "images_post_root.e",
            "images_post_root_slim.e",
            "images_post_slim.e",
            "options_post_responsive_root.e",
            "options_post_root.e",
            "poll_post_responsive_root.e",
            "poll_post_root.e",
            "post_base_wrapper",
            "post_base_wrapper_slim.e",
            "shared_post_responsive_root.e",
            "shared_post_root.e",
            "text_post_responsive_root.e",
            "text_post_root.e");

    private final ByteArrayFilterGroup liveBuffer = new ByteArrayFilterGroup(
            null, "yt_outline_live_black", "yt_outline_experimental_live_black");

    private String cachedKeywords = null;
    private List<byte[]> cachedKeywordBytes = new ArrayList<>();

    public BlockListFilter() {
        // Filters are created at app start: apply picker setting and pull the shared list.
        app.morphe.extension.shared.settings.preference.MiXplorerPicker.enabled =
                Settings.USE_MIXPLORER_PICKER.get();
        BlockListFileSync.syncSoon();
        addPathCallbacks(feedItems, posts);
    }

    private synchronized List<byte[]> keywordBytes() {
        String kw = Settings.BLOCK_LIVE_KEYWORDS.get();
        if (!kw.equals(cachedKeywords)) {
            Set<String> variations = new LinkedHashSet<>();
            for (String k : kw.split("[\\r\\n,]+")) {
                k = k.trim();
                if (k.length() >= 2 && k.startsWith("\"") && k.endsWith("\"")) k = k.substring(1, k.length() - 1);
                if (k.length() < 3) continue;
                variations.add(k);
                variations.add(k.toLowerCase(Locale.ROOT));
                variations.add(k.toUpperCase(Locale.ROOT));
                variations.add(Character.toUpperCase(k.charAt(0)) + k.substring(1).toLowerCase(Locale.ROOT));
            }
            List<byte[]> bytes = new ArrayList<>();
            for (String v : variations) bytes.add(v.getBytes(StandardCharsets.UTF_8));
            cachedKeywordBytes = bytes;
            cachedKeywords = kw;
        }
        return cachedKeywordBytes;
    }

    private static boolean isWordChar(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || b < 0;
    }

    /** True if the keyword is found as a whole word. */
    private static boolean containsWord(byte[] buffer, byte[] word) {
        int from = 0;
        while (true) {
            int i = FlyoutUtils.byteIndexOf(buffer, word, from);
            if (i < 0) return false;
            boolean before = i == 0 || !isWordChar(buffer[i - 1]);
            int end = i + word.length;
            boolean after = end >= buffer.length || !isWordChar(buffer[end]);
            if (before && after) return true;
            from = i + 1;
        }
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
        if (!BlockList.isPatchIncluded() || buffer == null || contentIndex != 0) return false;
        String p = path.toString();
        for (String skip : PARTS_TO_SKIP) {
            if (p.contains(skip)) return false;
        }

        List<BlockList.Target> channels = BlockList.getChannels();

        if (matchedGroup == posts) {
            app.morphe.extension.youtube.download.PostImages.onPostBuffer(buffer, asciiStrings.getStrings());
            return Settings.BLOCK_CHANNEL_POSTS.get() && !channels.isEmpty() && matches(channels, accessibility, buffer);
        }

        if (!channels.isEmpty() && matches(channels, accessibility, buffer)) return true;

        for (byte[] title : BlockList.getHiddenTitleBytes()) {
            if (FlyoutUtils.byteIndexOf(buffer, title) >= 0) return true;
        }

        List<BlockList.Target> live = BlockList.getLive();
        if (live.isEmpty() || !matches(live, accessibility, buffer)) return false;
        return liveBuffer.check(buffer).isFiltered() || hasLiveWord(buffer);
    }

    private boolean hasLiveWord(byte[] buffer) {
        for (byte[] k : keywordBytes()) {
            if (containsWord(buffer, k)) return true;
        }
        return false;
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
