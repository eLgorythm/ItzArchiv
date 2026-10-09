package me.fndlabs.itzarchiv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK_ARCHIVE = 1001;
    private static final int REQ_PICK_TREE = 1002;
    private static final int REQ_PICK_FILES = 1003;
    private static final int REQ_CREATE_ARCHIVE = 1004;
    private static final int REQ_PICK_FOLDER = 1005;
    private static final String PREFS_NAME = "itzarchiv_prefs";
    private static final String KEY_ABOUT_VERSION = "about_seen_version_code";
    private static final String REPO_URL = "https://github.com/eLgorythm/ItzArchiv";

    private TextView txtStatus, txtSelected, txtPercent, txtDialogPercent;
    private ProgressBar progress;
    private int currentPct = -1;
    private int currentDone = 0;
    private int currentTotal = -1;

    private String formatProgressText() {
        if (currentTotal > 0) {
            int pct = currentPct >= 0 ? currentPct : (int) Math.round(currentDone * 100.0 / currentTotal);
            return getString(R.string.progress_known, pct, currentDone, currentTotal);
        }
        if (currentDone > 0) return getString(R.string.progress_unknown, currentDone);
        return getString(R.string.progress_zero);
    }
    private boolean pendingIncludeHidden = false;
    private String pendingExtractPassword = "";
    private String pendingCompressPassword = "";
    private Button btnExtract, btnCompress, btnCompressFolder, btnCancel, btnShowLog, btnAbout;
    private Dialog logDialog;
    private Dialog nameDialog;
    private Dialog extractDialog;
    private Dialog aboutDialog;
    private TextView txtDialogStatus;
    private ProgressBar dialogProgress;
    private Button btnDialogCancel;
    private boolean isBusy = false;

    private Uri pendingArchiveUri;
    private Uri pendingFolderTreeUri;
    private Uri pendingOutputInitialUri;
    private List<Uri> pendingCompressUris;
    private String pendingFormat = "ZIP";

    private String progressLine = "";
    private String eventLine = "";
    private long lastProgressUi = 0;

    private final String[] formats = {"ZIP", "7Z", "TAR", "TAR.GZ", "TAR.BZ2", "TAR.XZ"};

    private final ArchiveService.Listener serviceListener = snap -> runOnUiThread(() -> {
        progressLine = snap.progressLine;
        eventLine = snap.eventLine;
        currentPct = snap.pct;
        currentDone = snap.done;
        currentTotal = snap.total;
        if (snap.running != isBusy) {
            // tampilkan % final 100% di dialog sebelum bar utama disembunyikan
            if (!snap.running && currentPct >= 100) {
                progress.setIndeterminate(false);
                progress.setProgress(100);
            }
            setBusy(snap.running);
        }
        if (snap.running) {
            if (snap.total > 0 && snap.pct >= 0) {
                progress.setIndeterminate(false);
                progress.setProgress(snap.pct);
            } else {
                progress.setIndeterminate(true);
            }
            txtPercent.setText(formatProgressText());
        }
        renderStatus();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        txtSelected = findViewById(R.id.txtSelected);
        txtPercent = findViewById(R.id.txtPercent);
        progress = findViewById(R.id.progress);
        btnExtract = findViewById(R.id.btnExtract);
        btnCompress = findViewById(R.id.btnCompress);
        btnCompressFolder = findViewById(R.id.btnCompressFolder);
        btnCancel = findViewById(R.id.btnCancel);
        btnShowLog = findViewById(R.id.btnShowLog);
        btnAbout = findViewById(R.id.btnAbout);

        btnExtract.setOnClickListener(v -> pickArchive());
        btnCompress.setOnClickListener(v -> pickFiles());
        btnCompressFolder.setOnClickListener(v -> pickFolder());
        btnCancel.setOnClickListener(v -> doCancel());
        btnShowLog.setOnClickListener(v -> showLogDialog());
        btnAbout.setOnClickListener(v -> showAboutDialog());

        // Izin notifikasi buat foreground service (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001); } catch (Exception ignored) {}
        }
        // Kalau service lagi jalan (mis. habis aplikasi ditutup lalu dibuka lagi), tampilin lagi statusnya
        eventLine = getString(R.string.ready_status);
        ArchiveService.Snapshot snap = ArchiveService.getSnapshot();
        if (snap.running || !snap.eventLine.isEmpty()) {
            serviceListener.onUpdate(snap);
        }

        Uri incoming = getIntent() != null ? getIntent().getData() : null;
        if (incoming != null) {
            pendingArchiveUri = incoming;
            try { getContentResolver().takePersistableUriPermission(incoming, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            String name = ArchiveEngine.getDisplayName(getContentResolver(), incoming);
            txtSelected.setText(getString(R.string.archive_selected, name));
            eventLine = getString(R.string.archive_password_destination_hint, name);
            renderStatus();
            showExtractDialog(name);
        } else {
            // About cuma muncul otomatis saat aplikasi dibuka normal, jangan ganggu alur "Buka dengan".
            showAboutIfNeeded();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ArchiveService.listener = serviceListener;
        ArchiveService.Snapshot snap = ArchiveService.getSnapshot();
        if (snap.running) serviceListener.onUpdate(snap);
    }

    @Override
    protected void onPause() {
        if (ArchiveService.listener == serviceListener) ArchiveService.listener = null;
        super.onPause();
    }

    private void pickArchive() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-7z-compressed", "application/x-tar",
                "application/gzip", "application/x-bzip2", "application/x-xz",
                "application/vnd.rar", "application/x-rar-compressed", "application/octet-stream"
        });
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_ARCHIVE);
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_TREE);
    }

    private void pickFiles() {
        pendingFolderTreeUri = null;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FILES);
    }

    private void pickFolder() {
        pendingCompressUris = null;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    /**
     * Hitung URI folder induk dari folder/file sumber.
     * Contoh: sumber /folder/image -> induknya /folder/.
     * Dialog simpan hasil kompres dibuka mulai di induk ini, BUKAN di dalam
     * folder yang lagi dikompres — biar hasilnya gak nyangkut di dalam sumbernya.
     */
    private static Uri parentInitialUri(Uri sourceUri, boolean isTree) {
        if (sourceUri == null) return null;
        try {
            String docId = isTree
                    ? DocumentsContract.getTreeDocumentId(sourceUri)
                    : DocumentsContract.getDocumentId(sourceUri);
            if (docId == null) return null;
            int colon = docId.indexOf(':');
            String volume = colon >= 0 ? docId.substring(0, colon) : "";
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String parentPath;
            int slash = path.lastIndexOf('/');
            if (slash > 0) parentPath = path.substring(0, slash);
            else parentPath = ""; // langsung di root volume
            String parentDocId = volume + ":" + parentPath;
            return DocumentsContract.buildDocumentUri(sourceUri.getAuthority(), parentDocId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Dialog makeDialog(int layoutRes) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(layoutRes);
        d.setCancelable(true);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            d.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            d.getWindow().setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    private int currentVersionCode() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "1.8";
        }
    }

    /** About muncul otomatis sekali untuk tiap versi; sesudahnya bisa dibuka dari tombol Tentang. */
    private void showAboutIfNeeded() {
        int versionCode = currentVersionCode();
        int seen = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_ABOUT_VERSION, -1);
        if (seen != versionCode) showAboutDialog();
    }

    private void showAboutDialog() {
        if (aboutDialog != null && aboutDialog.isShowing()) return;
        aboutDialog = makeDialog(R.layout.dialog_about);
        aboutDialog.setCanceledOnTouchOutside(true);
        aboutDialog.setCancelable(true);

        TextView versionView = aboutDialog.findViewById(R.id.txtAboutVersion);
        if (versionView != null) versionView.setText(getString(R.string.version_format, currentVersionName()));
        Button github = aboutDialog.findViewById(R.id.btnAboutGithub);
        github.setOnClickListener(v -> openRepository());

        // Sesuai revisi El: tidak ada tombol close/mulai. Cukup ketuk area kosong di luar card.
        aboutDialog.setOnDismissListener(d -> getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_ABOUT_VERSION, currentVersionCode())
                .apply());
        aboutDialog.show();
    }

    private void openRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
        } catch (Exception ignored) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ItzArchiv GitHub", REPO_URL));
            }
            android.widget.Toast.makeText(this, R.string.github_link_copied, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Dialog ekstrak: password tinggal di alur ekstrak, bukan di home. */
    private void showExtractDialog(String archiveName) {
        if (extractDialog != null && extractDialog.isShowing()) extractDialog.dismiss();
        if (aboutDialog != null && aboutDialog.isShowing()) aboutDialog.dismiss();
        extractDialog = makeDialog(R.layout.dialog_extract);
        TextView nameView = extractDialog.findViewById(R.id.txtExtractName);
        EditText edt = extractDialog.findViewById(R.id.edtExtractPassword);
        if (nameView != null) nameView.setText(archiveName != null ? archiveName : getString(R.string.archive_fallback_name));
        edt.setText(pendingExtractPassword);
        Button btnCancel = extractDialog.findViewById(R.id.btnExtractCancel);
        Button btnNext = extractDialog.findViewById(R.id.btnExtractNext);
        btnCancel.setOnClickListener(v -> {
            eventLine = getString(R.string.extract_cancelled_before_start);
            renderStatus();
            extractDialog.dismiss();
        });
        btnNext.setOnClickListener(v -> {
            pendingExtractPassword = edt.getText().toString();
            extractDialog.dismiss();
            pickTree();
        });
        extractDialog.show();
    }

    /** Dialog kompres terpadu: nama + format + hidden + password dalam satu tempat. */
    private void showCompressSettingsDialog(String baseName) {
        boolean folderMode = pendingFolderTreeUri != null;
        if (nameDialog != null && nameDialog.isShowing()) nameDialog.dismiss();
        nameDialog = makeDialog(R.layout.dialog_compress);
        EditText edt = nameDialog.findViewById(R.id.edtArchiveName);
        TextView extView = nameDialog.findViewById(R.id.txtArchiveExt);
        Spinner spinner = nameDialog.findViewById(R.id.spinnerFormatDialog);
        CheckBox hiddenBox = nameDialog.findViewById(R.id.checkHiddenDialog);
        EditText pwd = nameDialog.findViewById(R.id.edtCompressPassword);
        TextView pwdHint = nameDialog.findViewById(R.id.txtPasswordHint);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, formats);
        spinner.setAdapter(adapter);
        int cur = 0;
        for (int i = 0; i < formats.length; i++) if (formats[i].equals(pendingFormat)) { cur = i; break; }
        spinner.setSelection(cur);
        hiddenBox.setChecked(pendingIncludeHidden);
        hiddenBox.setVisibility(folderMode ? View.VISIBLE : View.GONE);
        pwd.setText(pendingCompressPassword);

        final Runnable syncDialog = () -> {
            String fmt = formats[spinner.getSelectedItemPosition()];
            if (extView != null) extView.setText(getString(R.string.archive_extension_auto, formatExtension(fmt)));
            boolean tar = fmt.startsWith("TAR");
            pwd.setEnabled(!tar);
            pwd.setAlpha(tar ? 0.4f : 1.0f);
            if (pwdHint != null) {
                pwdHint.setText(tar ? R.string.password_tar_unsupported : R.string.password_supported);
            }
        };
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) { syncDialog.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        syncDialog.run();

        String base = baseName != null && !baseName.isEmpty() ? baseName : getString(R.string.default_archive_name);
        for (String e : new String[]{".tar.gz", ".tar.bz2", ".tar.xz", ".zip", ".7z", ".tar"}) {
            if (base.toLowerCase().endsWith(e)) { base = base.substring(0, base.length() - e.length()); break; }
        }
        edt.setText(base);
        edt.setSelection(edt.getText().length());

        Button btnCancel = nameDialog.findViewById(R.id.btnCompressCancel);
        Button btnNext = nameDialog.findViewById(R.id.btnCompressNext);
        btnCancel.setOnClickListener(v -> {
            eventLine = getString(R.string.compress_cancelled_before_start);
            renderStatus();
            nameDialog.dismiss();
        });
        btnNext.setOnClickListener(v -> {
            String name = edt.getText().toString().trim();
            if (name.isEmpty()) name = getString(R.string.default_archive_name);
            pendingFormat = formats[spinner.getSelectedItemPosition()];
            pendingIncludeHidden = folderMode && hiddenBox.isChecked();
            pendingCompressPassword = pendingFormat.startsWith("TAR") ? "" : pwd.getText().toString();
            nameDialog.dismiss();
            createArchiveDoc(name);
        });
        nameDialog.show();
    }

    private static String formatExtension(String format) {
        if (format == null) return ".zip";
        switch (format) {
            case "7Z": return ".7z";
            case "TAR": return ".tar";
            case "TAR.GZ": return ".tar.gz";
            case "TAR.BZ2": return ".tar.bz2";
            case "TAR.XZ": return ".tar.xz";
            default: return ".zip";
        }
    }

    private void createArchiveDoc(String suggestedName) {
        String ext;
        String mime = "application/octet-stream";
        switch (pendingFormat) {
            case "7Z": ext = ".7z"; mime = "application/x-7z-compressed"; break;
            case "TAR": ext = ".tar"; mime = "application/x-tar"; break;
            case "TAR.GZ": ext = ".tar.gz"; mime = "application/gzip"; break;
            case "TAR.BZ2": ext = ".tar.bz2"; mime = "application/x-bzip2"; break;
            case "TAR.XZ": ext = ".tar.xz"; mime = "application/x-xz"; break;
            default: ext = ".zip"; mime = "application/zip"; break;
        }
        String title = (suggestedName != null && !suggestedName.isEmpty() ? suggestedName : getString(R.string.default_archive_name)) + ext;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, title);
        // Buka dialog simpan mulai di folder INDUK sumber (di luar folder yang dikompres)
        if (pendingOutputInitialUri != null) {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pendingOutputInitialUri);
        }
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_CREATE_ARCHIVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_PICK_TREE && pendingArchiveUri != null) {
                eventLine = getString(R.string.destination_not_selected);
                renderStatus();
            }
            return;
        }
        switch (requestCode) {
            case REQ_PICK_ARCHIVE: {
                pendingArchiveUri = data.getData();
                if (pendingArchiveUri != null) {
                    try { getContentResolver().takePersistableUriPermission(pendingArchiveUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    String name = ArchiveEngine.getDisplayName(getContentResolver(), pendingArchiveUri);
                    txtSelected.setText(getString(R.string.archive_selected, name));
                    eventLine = getString(R.string.archive_password_destination_hint, name);
                    renderStatus();
                    showExtractDialog(name);
                }
                break;
            }
            case REQ_PICK_TREE: {
                Uri treeUri = data.getData();
                if (treeUri != null && pendingArchiveUri != null) {
                    try {
                        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        if (flags != 0) getContentResolver().takePersistableUriPermission(treeUri, flags);
                    } catch (Exception ignored) {}
                    startExtract(treeUri);
                }
                break;
            }
            case REQ_PICK_FILES: {
                pendingCompressUris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int idx = 0; idx < data.getClipData().getItemCount(); idx++) {
                        Uri u = data.getClipData().getItemAt(idx).getUri();
                        pendingCompressUris.add(u);
                        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    }
                } else if (data.getData() != null) {
                    pendingCompressUris.add(data.getData());
                    try { getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                }
                pendingOutputInitialUri = pendingCompressUris.isEmpty() ? null : parentInitialUri(pendingCompressUris.get(0), false);
                txtSelected.setText(getString(R.string.files_selected, pendingCompressUris.size()));
                eventLine = getString(R.string.files_selected, pendingCompressUris.size());
                progressLine = "";
                renderStatus();
                showCompressSettingsDialog(getString(R.string.default_archive_name));
                break;
            }
            case REQ_PICK_FOLDER: {
                pendingFolderTreeUri = data.getData();
                if (pendingFolderTreeUri != null) {
                    try { getContentResolver().takePersistableUriPermission(pendingFolderTreeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    String folderName = ArchiveEngine.getDisplayName(getContentResolver(), pendingFolderTreeUri);
                    pendingOutputInitialUri = parentInitialUri(pendingFolderTreeUri, true);
                    txtSelected.setText(getString(R.string.folder_selected, folderName));
                    eventLine = getString(R.string.folder_selected_hint);
                    progressLine = "";
                    renderStatus();
                    showCompressSettingsDialog(folderName);
                }
                break;
            }
            case REQ_CREATE_ARCHIVE: {
                Uri out = data.getData();
                if (out != null) startCompress(out);
                break;
            }
        }
    }

    private void syncCancelButtons() {
        // Tombol Batalin cuma muncul pas proses jalan. Selain itu disembunyiin sekalian — lebih minimalis.
        if (btnDialogCancel != null) {
            btnDialogCancel.setVisibility(isBusy ? View.VISIBLE : View.GONE);
            btnDialogCancel.setEnabled(isBusy);
            btnDialogCancel.setText(R.string.cancel);
        }
    }

    private void doCancel() {
        // Batal bisa dari tombol di aplikasi, tombol di popup, atau tombol Batalin di notifikasi.
        // Service yang megang prosesnya — activity ditutup pun perintah batal tetap sampai.
        ArchiveService.requestCancel(this);
        eventLine = getString(R.string.cancelling_message);
        renderStatus();
    }

    private void showLogDialog() {
        runOnUiThread(() -> {
            if (logDialog == null) {
                logDialog = new Dialog(this);
                logDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                logDialog.setContentView(R.layout.dialog_log);
                logDialog.setCanceledOnTouchOutside(true);
                if (logDialog.getWindow() != null) {
                    logDialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    logDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                }
                txtDialogStatus = logDialog.findViewById(R.id.txtDialogStatus);
                txtDialogPercent = logDialog.findViewById(R.id.txtDialogPercent);
                dialogProgress = logDialog.findViewById(R.id.dialogProgress);
                btnDialogCancel = logDialog.findViewById(R.id.btnDialogCancel);
                Button btnX = logDialog.findViewById(R.id.btnDialogX);
                // Satu-satunya tombol tutup: ✕ kecil di pojok. Gak ada lagi tombol Tutup dobel.
                btnX.setOnClickListener(v -> logDialog.dismiss());
                btnDialogCancel.setOnClickListener(v -> doCancel());
            }
            // sinkronkan isi terakhir sebelum tampil
            String txt = progressLine.isEmpty() ? eventLine : progressLine + "\n" + eventLine;
            if (txtDialogStatus != null) txtDialogStatus.setText(txt);
            updateDialogProgress();
            syncCancelButtons();
            if (!logDialog.isShowing()) logDialog.show();
        });
    }

    /** Progress cuma tampil pas beneran ada kerjaan (atau barusan beres 100%). Selain itu disembunyiin. */
    private void updateDialogProgress() {
        if (dialogProgress == null) return;
        if (isBusy) {
            dialogProgress.setVisibility(View.VISIBLE);
            boolean unknown = currentTotal <= 0 && currentPct < 0;
            dialogProgress.setIndeterminate(unknown);
            dialogProgress.setProgress(currentPct >= 0 ? currentPct : 0);
            if (txtDialogPercent != null) {
                txtDialogPercent.setVisibility(View.VISIBLE);
                txtDialogPercent.setText(formatProgressText());
            }
        } else if (currentPct >= 100) {
            dialogProgress.setVisibility(View.VISIBLE);
            dialogProgress.setIndeterminate(false);
            dialogProgress.setProgress(100);
            if (txtDialogPercent != null) {
                txtDialogPercent.setVisibility(View.VISIBLE);
                txtDialogPercent.setText(formatProgressText());
            }
        } else {
            // Belum ada task / sudah selesai tanpa persen penuh — jangan muter-muter.
            dialogProgress.setVisibility(View.GONE);
            dialogProgress.setIndeterminate(false);
            if (txtDialogPercent != null) txtDialogPercent.setVisibility(View.GONE);
        }
    }

    private void renderStatus() {
        runOnUiThread(() -> {
            String txt = progressLine.isEmpty() ? eventLine : progressLine + "\n" + eventLine;
            if (txt.length() > 600) txt = txt.substring(0, 600);
            txtStatus.setText(txt);
            if (txtDialogStatus != null && logDialog != null && logDialog.isShowing()) {
                txtDialogStatus.setText(txt);
            }
            if (logDialog != null && logDialog.isShowing()) {
                updateDialogProgress();
            }
            syncCancelButtons();
        });
    }

    private void setBusy(boolean busy) {
        isBusy = busy;
        if (!busy && currentPct >= 100) {
            currentPct = 100;
        }
        if (busy) { currentPct = -1; currentDone = 0; currentTotal = -1; }
        runOnUiThread(() -> {
            btnExtract.setEnabled(!busy);
            btnCompress.setEnabled(!busy);
            btnCompressFolder.setEnabled(!busy);
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            txtPercent.setVisibility(busy ? View.VISIBLE : View.GONE);
            btnCancel.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) {
                progress.setIndeterminate(true);
                progress.setProgress(0);
                txtPercent.setText(R.string.progress_zero);
            } else if (currentPct == 100) {
                if (currentTotal > 0) currentDone = currentTotal;
                txtPercent.setText(formatProgressText());
            }
            syncCancelButtons();
        });
    }

    private void startExtract(Uri treeUri) {
        String password = pendingExtractPassword;
        Uri archive = pendingArchiveUri;
        progressLine = "";
        eventLine = getString(R.string.starting_extract);
        setBusy(true);
        renderStatus();
        showLogDialog();

        // Kerjaan dipindah ke ArchiveService (foreground service) — bukan nempel di Activity lagi.
        // Jadi aplikasi diminimize / ditutup / layar mati, ekstrak tetap jalan.
        // Batalin bisa dari popup, tombol di sini, atau tombol Batalin di notifikasi.
        Intent svc = new Intent(this, ArchiveService.class);
        svc.putExtra(ArchiveService.EXTRA_ACTION, ArchiveService.ACTION_EXTRACT);
        svc.putExtra(ArchiveService.EXTRA_ARCHIVE_URI, archive != null ? archive.toString() : null);
        svc.putExtra(ArchiveService.EXTRA_TREE_URI, treeUri != null ? treeUri.toString() : null);
        svc.putExtra(ArchiveService.EXTRA_PASSWORD, password);
        startForegroundService(svc);
    }

    private void startCompress(Uri outputUri) {
        String format = pendingFormat;
        Uri folderTree = pendingFolderTreeUri;
        List<Uri> fileInputs = pendingCompressUris != null ? new ArrayList<>(pendingCompressUris) : null;
        progressLine = "";
        eventLine = getString(R.string.starting_compress, format);
        setBusy(true);
        renderStatus();
        showLogDialog();

        Intent svc = new Intent(this, ArchiveService.class);
        svc.putExtra(ArchiveService.EXTRA_OUTPUT_URI, outputUri != null ? outputUri.toString() : null);
        svc.putExtra(ArchiveService.EXTRA_FORMAT, format);
        svc.putExtra(ArchiveService.EXTRA_PASSWORD, pendingCompressPassword);
        if (folderTree != null) {
            svc.putExtra(ArchiveService.EXTRA_ACTION, ArchiveService.ACTION_COMPRESS_FOLDER);
            svc.putExtra(ArchiveService.EXTRA_TREE_URI, folderTree.toString());
            svc.putExtra(ArchiveService.EXTRA_INCLUDE_HIDDEN, pendingIncludeHidden);
        } else if (fileInputs != null && !fileInputs.isEmpty()) {
            svc.putExtra(ArchiveService.EXTRA_ACTION, ArchiveService.ACTION_COMPRESS_FILES);
            ArrayList<String> strs = new ArrayList<>();
            for (Uri u : fileInputs) strs.add(u.toString());
            svc.putStringArrayListExtra(ArchiveService.EXTRA_FILE_URIS, strs);
        } else {
            eventLine = getString(R.string.no_input_selected);
            setBusy(false);
            renderStatus();
            return;
        }
        startForegroundService(svc);
    }

    @Override
    protected void onDestroy() {
        // PENTING: nutup Activity TIDAK ngebatalin kerjaan.
        // ArchiveService jalan sendiri; user bisa buka lagi dari notifikasi.
        if (ArchiveService.listener == serviceListener) ArchiveService.listener = null;
        if (logDialog != null && logDialog.isShowing()) logDialog.dismiss();
        if (nameDialog != null && nameDialog.isShowing()) nameDialog.dismiss();
        if (extractDialog != null && extractDialog.isShowing()) extractDialog.dismiss();
        if (aboutDialog != null && aboutDialog.isShowing()) aboutDialog.dismiss();
        super.onDestroy();
    }
}
