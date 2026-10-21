/*
 * Optional MiXplorer file/folder picker in place of the Android system picker (SAF).
 * MiXplorer answers the standard ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT_TREE
 * intents, so the intent is only addressed to its package.
 * Android 11+ hides other apps from getPackageInfo/resolveActivity unless declared in the manifest,
 * so installation is NOT checked: the intent is just started and the system picker is used
 * if MiXplorer is missing or refuses it.
 */
package app.morphe.extension.shared.settings.preference;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

import app.morphe.extension.shared.Logger;

public final class MiXplorerPicker {

    private static final String[] PACKAGES = {"com.mixplorer.silver", "com.mixplorer"};

    /** Set from the app's own setting. */
    public static volatile boolean enabled = false;

    private MiXplorerPicker() {}

    /** Starts the picker. Tries MiXplorer first when enabled, otherwise/else the system picker. */
    public static void start(Activity activity, android.app.Fragment fragment, Intent intent, int requestCode) {
        if (enabled) {
            for (String pkg : PACKAGES) {
                try {
                    Intent copy = new Intent(intent);
                    copy.setPackage(pkg);
                    fragment.startActivityForResult(copy, requestCode);
                    return;
                } catch (ActivityNotFoundException | SecurityException ex) {
                    Logger.printInfo(() -> pkg + " cannot handle " + intent.getAction() + ": " + ex.getMessage());
                } catch (Exception ex) {
                    Logger.printInfo(() -> pkg + " start failed", ex);
                }
            }
            app.morphe.extension.shared.Utils.showToastShort("MiXplorer could not open. Using the system picker.");
        }
        fragment.startActivityForResult(intent, requestCode);
    }
}
