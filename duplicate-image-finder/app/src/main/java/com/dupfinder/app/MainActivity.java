package com.dupfinder.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dupfinder.R;
import com.dupfinder.core.Fp;
import com.dupfinder.core.Grouper;
import com.dupfinder.core.Matcher;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 1;
    private static final int REQ_DELETE = 2;
    private static final int REQ_DELETE_ONE = 3;

    private static final int[] OPT_IDS = {R.id.opt_exact, R.id.opt_resized, R.id.opt_edited, R.id.opt_cropped};
    private static final int[] OPT_BITS = {Matcher.EXACT, Matcher.RESIZED, Matcher.EDITED, Matcher.CROPPED};
    private static final String[] OPT_KEYS = {"exact", "resized", "edited", "cropped"};
    private static final int[] OPT_TITLES = {R.string.opt_exact, R.string.opt_resized, R.string.opt_edited, R.string.opt_cropped};
    private static final int[] OPT_DESCS = {R.string.opt_exact_desc, R.string.opt_resized_desc, R.string.opt_edited_desc, R.string.opt_cropped_desc};

    private SharedPreferences prefs;
    private final CheckBox[] options = new CheckBox[OPT_IDS.length];
    private Button scanButton, deleteButton;
    private ProgressBar progress;
    private TextView status, usage, summary;
    private ThumbLoader thumbs;
    private final GroupAdapter adapter = new GroupAdapter();

    private Scanner scanner;                 // non-null while a scan runs
    private Scanner.Result result;           // last finished scan
    private boolean[] alive, selected;       // per image of the result
    private List<Grouper.Group> groups = new ArrayList<>();

    private List<Integer> pendingDelete;     // indices handed to the system delete dialog
    private int pendingPos, deletedCount;    // progress of the one-by-one flow (Android 10)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        thumbs = new ThumbLoader(getContentResolver(), dp(160));

        ListView list = findViewById(R.id.list);
        View header = LayoutInflater.from(this).inflate(R.layout.header, list, false);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);

        int secondary = resolveColor(android.R.attr.textColorSecondary);
        for (int i = 0; i < OPT_IDS.length; i++) {
            CheckBox cb = header.findViewById(OPT_IDS[i]);
            String title = getString(OPT_TITLES[i]), desc = getString(OPT_DESCS[i]);
            SpannableString text = new SpannableString(title + "\n" + desc);
            text.setSpan(new RelativeSizeSpan(0.82f), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(secondary), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cb.setText(text);
            cb.setChecked(prefs.getBoolean(OPT_KEYS[i], true));
            final String key = OPT_KEYS[i];
            cb.setOnCheckedChangeListener((v, checked) -> {
                prefs.edit().putBoolean(key, checked).apply();
                if (result != null) { regroup(true); showResultStatus(); }
            });
            options[i] = cb;
        }
        scanButton = header.findViewById(R.id.btn_scan);
        progress = header.findViewById(R.id.progress);
        status = header.findViewById(R.id.status);
        usage = header.findViewById(R.id.usage);
        summary = findViewById(R.id.summary);
        deleteButton = findViewById(R.id.btn_delete);

        scanButton.setOnClickListener(v -> {
            if (scanner != null) scanner.cancel();
            else if (hasReadPermission()) startScan();
            else requestPermissions(neededPermissions(), REQ_PERMISSION);
        });
        findViewById(R.id.btn_auto).setOnClickListener(v -> { if (result != null) regroup(true); });
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            if (selected == null) return;
            java.util.Arrays.fill(selected, false);
            adapter.notifyDataSetChanged();
            updateSummary();
        });
        deleteButton.setOnClickListener(v -> confirmDelete());
        updateSummary();
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.cancel();
        thumbs.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- permissions

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 34)
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        if (Build.VERSION.SDK_INT >= 29) return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= 34)
            return granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        if (Build.VERSION.SDK_INT >= 33) return granted(Manifest.permission.READ_MEDIA_IMAGES);
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMISSION) return;
        if (hasReadPermission()) { startScan(); return; }
        status.setText(R.string.perm_needed);
        new AlertDialog.Builder(this)
                .setMessage(R.string.perm_needed)
                .setPositiveButton(R.string.perm_settings, (d, w) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- scanning

    private void startScan() {
        scanButton.setText(R.string.scan_stop);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        for (CheckBox cb : options) cb.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scanner = new Scanner(this, new Scanner.Listener() {
            @Override public void onStatus(String text, int permille) {
                status.setText(text);
                progress.setProgress(permille);
            }
            @Override public void onDone(Scanner.Result r) {
                scanFinished();
                result = r;
                alive = new boolean[r.items.length];
                java.util.Arrays.fill(alive, true);
                selected = new boolean[r.items.length];
                regroup(true);
                showResultStatus();
            }
            @Override public void onCancelled() {
                scanFinished();
                status.setText(R.string.status_cancelled);
            }
            @Override public void onError(Throwable t) {
                scanFinished();
                status.setText(getString(R.string.status_failed, String.valueOf(t.getMessage())));
            }
        });
        scanner.start();
    }

    private void scanFinished() {
        scanner = null;
        scanButton.setText(R.string.scan_start);
        progress.setVisibility(View.GONE);
        for (CheckBox cb : options) cb.setEnabled(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showResultStatus() {
        String done = getString(R.string.status_done, result.items.length, result.seconds);
        status.setText(groups.isEmpty() ? done + "\n" + getString(R.string.status_none) : done);
        usage.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int enabledMask() {
        int mask = 0;
        for (int i = 0; i < options.length; i++) if (options[i].isChecked()) mask |= OPT_BITS[i];
        return mask;
    }

    /** Rebuilds the groups from the stored pair matches; no rescan is needed when a setting changes. */
    private void regroup(boolean autoSelect) {
        groups = Grouper.build(result.fps, result.dates, alive, result.edges, enabledMask());
        boolean[] visible = new boolean[selected.length];
        if (autoSelect) java.util.Arrays.fill(selected, false);
        for (Grouper.Group g : groups) {
            for (int k = 0; k < g.members.length; k++) {
                visible[g.members[k]] = true;
                if (autoSelect && k > 0) selected[g.members[k]] = true; // keep the reference, mark the rest
            }
        }
        // never leave something selected that is not on screen
        for (int i = 0; i < selected.length; i++) selected[i] &= visible[i];
        adapter.notifyDataSetChanged();
        updateSummary();
    }

    private void updateSummary() {
        int count = 0;
        long bytes = 0;
        if (selected != null) {
            for (int i = 0; i < selected.length; i++) {
                if (selected[i]) { count++; bytes += result.items[i].size; }
            }
        }
        if (result == null) summary.setText(R.string.summary_empty);
        else summary.setText(getString(R.string.summary, groups.size(), count, Formatter.formatShortFileSize(this, bytes)));
        deleteButton.setEnabled(count > 0);
        deleteButton.setAlpha(count > 0 ? 1f : 0.4f);
        deleteButton.setText(count > 0 ? getString(R.string.btn_delete) + " (" + count + ")" : getString(R.string.btn_delete));
    }

    // ---------------------------------------------------------------- deleting

    private void confirmDelete() {
        if (selected == null) return;
        final List<Integer> sel = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) if (selected[i]) sel.add(i);
        if (sel.isEmpty()) {
            Toast.makeText(this, R.string.nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        int emptied = 0;
        for (Grouper.Group g : groups) {
            boolean all = true;
            for (int m : g.members) all &= selected[m];
            if (all) emptied++;
        }
        // Android 11+ shows its own confirmation, so ours is only needed for the warning there.
        if (Build.VERSION.SDK_INT >= 30 && emptied == 0) { delete(sel); return; }
        String msg = getString(R.string.delete_msg, sel.size());
        if (emptied > 0) msg += "\n\n" + getString(R.string.delete_all_warning, emptied);
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(msg)
                .setPositiveButton(R.string.delete_ok, (d, w) -> delete(sel))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void delete(List<Integer> sel) {
        pendingDelete = sel;
        if (Build.VERSION.SDK_INT >= 30) {
            List<Uri> uris = new ArrayList<>();
            for (int i : sel) uris.add(result.items[i].uri);
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
            }
            return;
        }
        pendingPos = 0;
        deletedCount = 0;
        deleteOneByOne();
    }

    /** Android 8-10: delete directly; Android 10 may ask for consent per file not created by this app. */
    private void deleteOneByOne() {
        ContentResolver cr = getContentResolver();
        while (pendingPos < pendingDelete.size()) {
            int idx = pendingDelete.get(pendingPos);
            try {
                cr.delete(result.items[idx].uri, null, null);
            } catch (SecurityException e) {
                if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) {
                    try {
                        IntentSender sender = ((RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender();
                        startIntentSenderForResult(sender, REQ_DELETE_ONE, null, 0, 0, 0);
                        return; // continues in onActivityResult
                    } catch (IntentSender.SendIntentException ignored) { }
                }
            } catch (Exception ignored) { }
            pendingPos++;
        }
        afterDelete();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (pendingDelete == null) return;
        if (requestCode == REQ_DELETE) {
            afterDelete(); // also verifies what is really gone when the dialog was cancelled
        } else if (requestCode == REQ_DELETE_ONE) {
            if (resultCode == RESULT_OK) deleteOneByOne(); // consent given: retry the same file
            else afterDelete();
        }
    }

    private void afterDelete() {
        int gone = 0, failed = 0;
        for (int idx : pendingDelete) {
            if (exists(result.items[idx].uri)) { failed++; continue; }
            alive[idx] = false;
            selected[idx] = false;
            gone++;
        }
        pendingDelete = null;
        regroup(false);
        if (gone > 0) Toast.makeText(this, getString(R.string.deleted_toast, gone), Toast.LENGTH_SHORT).show();
        else if (failed > 0 && Build.VERSION.SDK_INT < 30)
            Toast.makeText(this, getString(R.string.delete_failed_toast, failed), Toast.LENGTH_SHORT).show();
    }

    private boolean exists(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- list

    private final class GroupAdapter extends BaseAdapter {
        @Override public int getCount() { return groups.size(); }
        @Override public Object getItem(int position) { return groups.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View row, ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
            if (row == null) row = inflater.inflate(R.layout.group_row, parent, false);
            Grouper.Group g = groups.get(position);
            ((TextView) row.findViewById(R.id.title)).setText(getString(R.string.group_title, position + 1, g.members.length));
            LinearLayout cells = row.findViewById(R.id.cells);
            while (cells.getChildCount() > g.members.length) cells.removeViewAt(cells.getChildCount() - 1);
            while (cells.getChildCount() < g.members.length) cells.addView(inflater.inflate(R.layout.cell, cells, false));
            for (int k = 0; k < g.members.length; k++) bindCell(cells.getChildAt(k), g, k);
            ((HorizontalScrollView) row.findViewById(R.id.scroll)).scrollTo(0, 0);
            return row;
        }
    }

    private void bindCell(final View cell, Grouper.Group g, int k) {
        final int idx = g.members[k];
        final ImageItem item = result.items[idx];
        Fp fp = result.fps[idx], ref = result.fps[g.ref];
        thumbs.load(item, (ImageView) cell.findViewById(R.id.img));

        TextView badge = cell.findViewById(R.id.badge);
        int label = g.labels[k], text, color;
        if (label == 0) { text = R.string.label_ref; color = R.color.badge_ref; }
        else if ((label & Matcher.CROPPED) != 0) { text = R.string.label_cropped; color = R.color.badge_cropped; }
        else if ((label & Matcher.EDITED) != 0) { text = R.string.label_edited; color = R.color.badge_edited; }
        else if ((label & Matcher.RESIZED) != 0) {
            text = fp.w == ref.w && fp.h == ref.h ? R.string.label_quality : R.string.label_resized;
            color = R.color.badge_resized;
        } else { text = R.string.label_exact; color = R.color.badge_exact; }
        badge.setText(text);
        badge.setBackgroundTintList(ColorStateList.valueOf(getColor(color)));

        ((TextView) cell.findViewById(R.id.info)).setText(
                fp.w + "×" + fp.h + " · " + Formatter.formatShortFileSize(this, item.size));
        ((TextView) cell.findViewById(R.id.name)).setText(item.folder == null ? item.name : item.folder + "/" + item.name);
        showSelection(cell, selected[idx]);

        cell.setOnClickListener(v -> {
            selected[idx] = !selected[idx];
            showSelection(cell, selected[idx]);
            updateSummary();
        });
        cell.setOnLongClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(item.uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception ignored) { }
            return true;
        });
    }

    private static void showSelection(View cell, boolean isSelected) {
        ((CheckBox) cell.findViewById(R.id.check)).setChecked(isSelected);
        cell.findViewById(R.id.tint).setVisibility(isSelected ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int resolveColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? getColor(tv.resourceId) : tv.data;
    }
}
