package app.morphe.extension.youtube.download;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;

/**
 * 69 mod: "choose images to download" window.
 * Only the images scroll; the buttons always stay visible. Nothing is ticked at the start.
 */
public final class ImagePicker {

    public static final class Item {
        public final String label;
        public final Bitmap preview;
        boolean selected;

        public Item(String label, Bitmap preview) {
            this.label = label;
            this.preview = preview;
        }
    }

    public interface OnPicked {
        void picked(List<Integer> indexes);
    }

    private ImagePicker() {}

    public static void show(String title, List<Item> items, OnPicked onPicked) {
        Activity a = Utils.getActivity();
        if (a == null) return;
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        boolean dark = Utils.isDarkModeEnabled();
        int bg = dark ? 0xFF212121 : 0xFFFFFFFF;
        int fg = dark ? 0xFFFFFFFF : 0xFF000000;

        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(16 * d);
        root.setBackground(shape);
        root.setPadding(pad, pad, pad, pad / 2);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setMaxLines(2);
        t.setPadding(0, 0, 0, pad / 2);
        root.addView(t);

        TextView count = new TextView(a);
        count.setTextColor(fg);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(count);

        // Two images per row, scrolling.
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<CheckBox> boxes = new ArrayList<>();
        Button download = new Button(a);
        Runnable refresh = () -> {
            int n = 0;
            for (Item it : items) if (it.selected) n++;
            count.setText(n + " of " + items.size() + " selected");
            download.setEnabled(n > 0);
            download.setText(n > 0 ? "Download (" + n + ")" : "Download");
        };
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i % 2 == 0) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(pad / 3, pad / 3, pad / 3, pad / 3);
            FrameLayout frame = new FrameLayout(a);
            ImageView img = new ImageView(a);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(it.preview);
            frame.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (110 * d)));
            CheckBox box = new CheckBox(a);
            box.setChecked(false);
            box.setBackgroundColor(0x88000000);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            frame.addView(box, blp);
            box.setOnCheckedChangeListener((b, on) -> {
                it.selected = on;
                refresh.run();
            });
            img.setOnClickListener(v -> box.toggle());
            boxes.add(box);
            cell.addView(frame);
            TextView label = new TextView(a);
            label.setText(it.label);
            label.setTextColor(fg);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setMaxLines(1);
            cell.addView(label);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (items.size() % 2 == 1 && row != null) {
            row.addView(new LinearLayout(a), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Fixed button bar.
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        Button all = new Button(a);
        all.setText("Select all");
        all.setOnClickListener(v -> {
            boolean any = false;
            for (Item it : items) if (!it.selected) any = true;
            for (CheckBox b : boxes) b.setChecked(any);
        });
        Button cancel = new Button(a);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> dialog.dismiss());
        download.setOnClickListener(v -> {
            List<Integer> picked = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) if (items.get(i).selected) picked.add(i);
            dialog.dismiss();
            if (!picked.isEmpty()) onPicked.picked(picked);
        });
        for (Button b : new Button[]{all, cancel, download}) {
            b.setAllCaps(false);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setTextColor(fg);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(bar);
        refresh.run();

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int h = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.8f);
            w.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.94f), h);
        }
        dialog.show();
    }
}
