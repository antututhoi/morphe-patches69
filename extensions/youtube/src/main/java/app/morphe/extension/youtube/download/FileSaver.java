package app.morphe.extension.youtube.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import app.morphe.extension.shared.Utils;

/** Saves into Download/&lt;folder&gt;/&lt;name&gt;. No SAF: MediaStore on Android 10+. */
public final class FileSaver {

    private FileSaver() {}

    public static String sanitize(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        t = t.replaceAll("\\s+", " ").replaceAll("^\\.+", "");
        if (t.length() > 120) t = t.substring(0, 120).trim();
        return t.isEmpty() ? "unnamed" : t;
    }

    /** @return a readable location for messages. */
    public static String save(String relativeFolder, String fileName, String mime, byte[] data) throws Exception {
        Context c = Utils.getContext();
        String tree = app.morphe.extension.youtube.settings.Settings.DOWNLOAD_FOLDER_URI.get();
        if (!tree.isEmpty()) {
            return saveToTree(c, Uri.parse(tree), relativeFolder, fileName, mime, data);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeFolder);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("MediaStore refused to create " + fileName);
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("Cannot open " + uri);
                out.write(data);
            }
            return "Download/" + relativeFolder + "/" + fileName;
        }
        // Android 9: app-specific external folder (no storage permission needed).
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), relativeFolder);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create " + dir);
        File f = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f.getAbsolutePath();
    }

    /** Writes into the folder the user picked (e.g. with MiXplorer): <picked>/<channel>/<file>. */
    private static String saveToTree(Context c, Uri tree, String subFolder, String fileName, String mime, byte[] data)
            throws Exception {
        ContentResolver cr = c.getContentResolver();
        Uri root = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
        Uri dir = root;
        for (String part : subFolder.split("/")) {
            if (part.isEmpty()) continue;
            dir = findOrCreateChild(cr, tree, dir, part, android.provider.DocumentsContract.Document.MIME_TYPE_DIR);
        }
        Uri file = findChild(cr, tree, dir, fileName);
        if (file == null) {
            file = android.provider.DocumentsContract.createDocument(cr, dir, mime, fileName);
        }
        if (file == null) throw new java.io.IOException("Cannot create " + fileName + " in the chosen folder");
        try (OutputStream out = cr.openOutputStream(file, "wt")) {
            if (out == null) throw new java.io.IOException("Cannot open " + file);
            out.write(data);
        }
        return tree.getLastPathSegment() + "/" + subFolder + "/" + fileName;
    }

    private static Uri findChild(ContentResolver cr, Uri tree, Uri parent, String name) {
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, android.provider.DocumentsContract.getDocumentId(parent));
        try (android.database.Cursor cur = cr.query(children, new String[]{
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (name.equals(cur.getString(1))) {
                    return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                }
            }
        }
        return null;
    }

    private static Uri findOrCreateChild(ContentResolver cr, Uri tree, Uri parent, String name, String mime)
            throws Exception {
        Uri found = findChild(cr, tree, parent, name);
        if (found != null) return found;
        Uri created = android.provider.DocumentsContract.createDocument(cr, parent, mime, name);
        if (created == null) throw new java.io.IOException("Cannot create folder " + name);
        return created;
    }
}
