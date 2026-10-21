/*
 * Optional MiXplorer file picker in place of the Android system picker (SAF).
 * MiXplorer answers the standard ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT / ACTION_GET_CONTENT
 * intents, so the only change is addressing the intent to its package.
 * If MiXplorer is not installed or refuses the intent the system picker is used.
 */
package app.morphe.extension.shared.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import app.morphe.extension.shared.Logger;

public final class MiXplorerPicker {

    private static final String[] PACKAGES = {"com.mixplorer.silver", "com.mixplorer"};

    /** Set from the app's own setting. */
    public static volatile boolean enabled = false;

    private MiXplorerPicker() {}

    public static String installedPackage(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String p : PACKAGES) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** Addresses the intent to MiXplorer when enabled and installed and it can handle the intent. */
    public static Intent prefer(Context context, Intent intent) {
        if (!enabled) return intent;
        try {
            String pkg = installedPackage(context);
            if (pkg == null) return intent;
            Intent copy = new Intent(intent);
            copy.setPackage(pkg);
            if (copy.resolveActivity(context.getPackageManager()) != null) return copy;
        } catch (Exception ex) {
            Logger.printInfo(() -> "MiXplorer unavailable, using system picker", ex);
        }
        return intent;
    }

    /** Starts the picker. If MiXplorer fails to start the system picker is used. */
    public static void start(Activity activity, android.app.Fragment fragment, Intent intent, int requestCode) {
        Intent preferred = prefer(activity, intent);
        try {
            fragment.startActivityForResult(preferred, requestCode);
        } catch (Exception ex) {
            if (preferred == intent) throw ex;
            Logger.printInfo(() -> "MiXplorer start failed, using system picker", ex);
            fragment.startActivityForResult(intent, requestCode);
        }
    }
}
