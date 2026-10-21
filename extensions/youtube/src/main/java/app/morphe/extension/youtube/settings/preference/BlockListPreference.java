/*
 * Settings entry: manage the block list, choose/sync the shared sync file, MiXplorer option.
 */
package app.morphe.extension.youtube.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.youtube.blocklist.BlockList;
import app.morphe.extension.youtube.blocklist.BlockListFileSync;
import app.morphe.extension.youtube.blocklist.BlockListSync;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class BlockListPreference extends Preference {

    public BlockListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes); init();
    }
    public BlockListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }
    public BlockListPreference(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public BlockListPreference(Context context) { super(context); init(); }

    private void init() {
        setOnPreferenceClickListener(p -> { showMenu(p.getContext()); return true; });
    }

    private static void showMenu(Context c) {
        boolean hasSync = !Settings.BLOCK_LIST_SYNC_URI.get().isEmpty();
        String[] items = {
                "Blocked channels (" + BlockList.getChannels().size() + ")",
                "Blocked livestreams (" + BlockList.getLive().size() + ")",
                hasSync ? "Sync now" : "Sync now (choose a file first)",
                "Choose sync file (multitool-sync.json)" + (hasSync ? " ✓" : ""),
                "Use MiXplorer as file picker: " + (Settings.USE_MIXPLORER_PICKER.get() ? "ON" : "OFF"),
        };
        new AlertDialog.Builder(c).setTitle("Block list").setItems(items, (d, which) -> {
            switch (which) {
                case 0: showList(c, BlockListSync.CHANNELS); break;
                case 1: showList(c, BlockListSync.LIVE); break;
                case 2: syncNow(); break;
                case 3: chooseFile(); break;
                case 4:
                    Settings.USE_MIXPLORER_PICKER.save(!Settings.USE_MIXPLORER_PICKER.get());
                    app.morphe.extension.shared.settings.preference.MiXplorerPicker.enabled =
                            Settings.USE_MIXPLORER_PICKER.get();
                    showMenu(c);
                    break;
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showList(Context c, String list) {
        List<BlockList.Target> targets = list.equals(BlockListSync.LIVE) ? BlockList.getLive() : BlockList.getChannels();
        if (targets.isEmpty()) { Utils.showToastShort("Empty"); return; }
        List<String> names = new ArrayList<>();
        for (BlockList.Target t : targets) names.add(t.name);
        new AlertDialog.Builder(c).setTitle("Tap to unblock").setItems(names.toArray(new String[0]), (d, i) -> {
            BlockList.remove(list, targets.get(i).key);
            BlockListFileSync.syncSoon();
            showList(c, list);
        }).setNegativeButton(android.R.string.ok, null).show();
    }

    private static void syncNow() {
        Utils.runOnBackgroundThread(() -> {
            String err = BlockListFileSync.syncNow();
            Utils.runOnMainThread(() -> Utils.showToastLong(err == null ? "Synced" : "Sync failed: " + err));
        });
    }

    private static void chooseFile() {
        AbstractPreferenceFragment f = AbstractPreferenceFragment.instance.get();
        if (f == null) return;
        f.pickSyncFileActivity((Uri uri) -> {
            Settings.BLOCK_LIST_SYNC_URI.save(uri.toString());
            syncNow();
        });
    }
}
