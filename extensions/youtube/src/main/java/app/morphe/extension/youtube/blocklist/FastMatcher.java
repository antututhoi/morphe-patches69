/*
 * 69 mod: finds any of many words / channel ids in a feed item's buffer in ONE pass.
 * The old way searched the whole buffer once per blocked channel and once per hidden title,
 * which made scrolling slow and caused "isn't responding" with long lists.
 */
package app.morphe.extension.youtube.blocklist;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FastMatcher {
    /** First two bytes of any word (65536 bits = 8 KB). */
    private final long[] first2 = new long[1024];
    /** First three bytes -> words starting with them. */
    private final HashMap<Integer, List<byte[]>> byPrefix = new HashMap<>();
    private final Set<String> channelIds;
    private final boolean empty;

    public FastMatcher(List<byte[]> words, Set<String> channelIds) {
        for (byte[] w : words) {
            if (w == null || w.length < 3) continue;
            int k2 = ((w[0] & 0xFF) << 8) | (w[1] & 0xFF);
            first2[k2 >>> 6] |= 1L << (k2 & 63);
            int k3 = (k2 << 8) | (w[2] & 0xFF);
            List<byte[]> l = byPrefix.get(k3);
            if (l == null) byPrefix.put(k3, l = new ArrayList<>(1));
            l.add(w);
        }
        this.channelIds = channelIds;
        this.empty = byPrefix.isEmpty() && channelIds.isEmpty();
    }

    public static FastMatcher of(List<BlockList.Target> targets) {
        List<byte[]> names = new ArrayList<>();
        Set<String> cids = new HashSet<>();
        for (BlockList.Target t : targets) {
            if (t.nameBytes != null) names.add(t.nameBytes);
            if (t.cid != null && !t.cid.isEmpty()) cids.add(t.cid);
        }
        return new FastMatcher(names, cids);
    }

    public boolean isEmpty() {
        return empty;
    }

    private static boolean idChar(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || b == '_' || b == '-';
    }

    /** True if the buffer holds one of the words or one of the channel ids ("UC" + 22 characters). */
    public boolean matches(byte[] buf) {
        if (empty || buf == null) return false;
        final int n = buf.length;
        final boolean ids = !channelIds.isEmpty();
        for (int i = 0; i + 2 < n; i++) {
            int b0 = buf[i] & 0xFF, b1 = buf[i + 1] & 0xFF;
            if (ids && b0 == 'U' && b1 == 'C' && i + 24 <= n) {
                boolean ok = true;
                for (int j = i + 2; j < i + 24; j++) {
                    if (!idChar(buf[j])) { ok = false; break; }
                }
                if (ok && channelIds.contains(new String(buf, i, 24, StandardCharsets.US_ASCII))) return true;
            }
            int k2 = (b0 << 8) | b1;
            if ((first2[k2 >>> 6] & (1L << (k2 & 63))) == 0) continue;
            List<byte[]> cands = byPrefix.get((k2 << 8) | (buf[i + 2] & 0xFF));
            if (cands == null) continue;
            for (byte[] w : cands) {
                if (i + w.length > n) continue;
                int j = 3;
                while (j < w.length && buf[i + j] == w[j]) j++;
                if (j == w.length) return true;
            }
        }
        return false;
    }

    /** Only the channel ids (for comments, where a name could be inside someone else's text). */
    public boolean matchesId(byte[] buf) {
        if (channelIds.isEmpty() || buf == null) return false;
        final int n = buf.length;
        for (int i = 0; i + 24 <= n; i++) {
            if (buf[i] != 'U' || buf[i + 1] != 'C') continue;
            boolean ok = true;
            for (int j = i + 2; j < i + 24; j++) {
                if (!idChar(buf[j])) { ok = false; break; }
            }
            if (ok && channelIds.contains(new String(buf, i, 24, StandardCharsets.US_ASCII))) return true;
        }
        return false;
    }
}
