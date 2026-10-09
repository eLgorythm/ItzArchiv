package me.fndlabs.itzarchiv;

import android.content.ContentResolver;
import android.content.res.Resources;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ArchiveEngine {

    public interface Callback {
        void onLog(String msg);
        void onProgress(String current, int done, int total);
        default boolean isCancelled() { return false; }
    }

    public static class Item {
        public final Uri uri;
        public final String path;
        public final long size;
        public final boolean dir;
        public Item(Uri uri, String path, long size, boolean dir) {
            this.uri = uri; this.path = path; this.size = size; this.dir = dir;
        }
    }

    private static final int BUF = 8192;
    private static volatile Resources resources;

    private static String text(int resId, Object... args) {
        Resources r = resources;
        if (r == null) return "";
        return args.length == 0 ? r.getString(resId) : r.getString(resId, args);
    }

    private static void checkCancelled(Callback cb) throws IOException {
        if (cb.isCancelled()) throw new IOException(text(R.string.cancelled_exception));
    }

    public static String getDisplayName(ContentResolver resolver, Uri uri) {
        String name = null;
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) {
            String p = uri.getLastPathSegment();
            name = p != null ? p : text(R.string.archive_fallback_name);
            int s = name.lastIndexOf('/');
            if (s >= 0) name = name.substring(s + 1);
            try { name = Uri.decode(name); } catch (Exception ignored) {}
        }
        return name;
    }

    private static String getDocDisplayName(ContentResolver resolver, Uri docUri) {
        try (Cursor c = resolver.query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    private static long getStatSize(ContentResolver resolver, Uri uri) {
        try (android.os.ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
            if (pfd != null) return pfd.getStatSize();
        } catch (Exception ignored) {}
        return -1;
    }

    private static long getSize(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static File copyToCache(Context ctx, ContentResolver resolver, Uri uri, String name, Callback cb) throws IOException {
        File dir = new File(ctx.getCacheDir(), "work");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "in_" + System.currentTimeMillis() + "_" + name.replaceAll("[^a-zA-Z0-9._-]", "_"));
        resources = ctx.getResources();
        cb.onLog(text(R.string.copying_archive_temp));
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            if (in == null) throw new IOException(text(R.string.cannot_open_archive));
            copy(in, out, cb);
        }
        return f;
    }

    private static long copy(InputStream in, OutputStream out, Callback cb) throws IOException {
        byte[] buf = new byte[BUF];
        int n;
        long total = 0;
        while ((n = in.read(buf)) != -1) {
            checkCancelled(cb);
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        return total;
    }

    // ---------------- KUMPULIN FOLDER ----------------

    public static List<Item> collectFolder(Context ctx, Uri treeUri, Callback cb) throws IOException {
        return collectFolder(ctx, treeUri, cb, false);
    }

    public static List<Item> collectFolder(Context ctx, Uri treeUri, Callback cb, boolean includeHidden) throws IOException {
        resources = ctx.getResources();
        ContentResolver resolver = ctx.getContentResolver();
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId);
        String rootName = getDocDisplayName(resolver, rootDocUri);
        if (rootName == null || rootName.isEmpty()) rootName = text(R.string.folder_fallback_name);
        List<Item> items = new ArrayList<>();
        // Folder akarnya sendiri selalu disertakan, walau namanya tersembunyi — user yang milih dia.
        items.add(new Item(rootDocUri, rootName, 0, true));
        int[] skipped = {0};
        int[] skippedHidden = {0};
        cb.onLog(text(R.string.collecting_folder, rootName, includeHidden ? text(R.string.including_hidden_suffix) : ""));
        walkFolder(resolver, treeUri, rootDocId, rootName, items, skipped, skippedHidden, includeHidden, cb);
        if (skipped[0] > 0) cb.onLog(text(R.string.skipped_trashed, skipped[0]));
        if (skippedHidden[0] > 0) cb.onLog(text(R.string.skipped_hidden, skippedHidden[0]));
        return items;
    }

    private static void walkFolder(ContentResolver resolver, Uri treeUri, String parentDocId, String parentPath,
                                   List<Item> items, int[] skipped, int[] skippedHidden, boolean includeHidden, Callback cb) throws IOException {
        checkCancelled(cb);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] cols = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor c = resolver.query(childrenUri, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                checkCancelled(cb);
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? -1 : c.getLong(3);
                if (name == null || name.isEmpty()) continue;
                if (name.startsWith(".trashed-") || name.equals(".trash") || name.equals(".Trash")) {
                    skipped[0]++;
                    continue;
                }
                // File/folder tersembunyi (diawali titik) cuma disertakan kalau user nyentang opsinya.
                if (!includeHidden && name.startsWith(".") && name.length() > 1) {
                    skippedHidden[0]++;
                    continue;
                }
                String rel = parentPath + "/" + name;
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    items.add(new Item(docUri, rel, 0, true));
                    walkFolder(resolver, treeUri, docId, rel, items, skipped, skippedHidden, includeHidden, cb);
                } else {
                    items.add(new Item(docUri, rel, size, false));
                    if (items.size() % 50 == 0) cb.onProgress(rel, items.size(), -1);
                }
            }
        }
    }

    public static List<Item> itemsFromFiles(ContentResolver resolver, List<Uri> inputs, Callback cb) {
        List<Item> items = new ArrayList<>();
        int skipped = 0;
        for (Uri uri : inputs) {
            String name = getDisplayName(resolver, uri);
            if (name.startsWith(".trashed-")) { skipped++; continue; }
            items.add(new Item(uri, name, getSize(resolver, uri), false));
        }
        if (skipped > 0) cb.onLog(text(R.string.skipped_trashed, skipped));
        return items;
    }

    // ---------------- EKSTRAK ----------------

    public static void extract(Context ctx, Uri archiveUri, Uri treeUri, String password, Callback cb) throws Exception {
        resources = ctx.getResources();
        ContentResolver resolver = ctx.getContentResolver();
        String name = getDisplayName(resolver, archiveUri);
        String lower = name.toLowerCase();
        File temp = copyToCache(ctx, resolver, archiveUri, name, cb);
        SafWriter writer = new SafWriter(ctx, treeUri);
        try {
            cb.onLog(text(R.string.opening_archive, name));
            if (lower.endsWith(".zip")) {
                if (password != null && !password.isEmpty()) extractZipWithPassword(ctx, temp, writer, password, cb);
                else extractZip(temp, writer, cb);
            } else if (lower.endsWith(".7z")) {
                extract7z(temp, writer, password, cb);
            } else if (lower.endsWith(".rar")) {
                extractRar(temp, writer, password, cb);
            } else if (lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                    || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz")
                    || lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
                extractTar(temp, lower, writer, cb);
            } else if (lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz")) {
                extractSingle(temp, name, lower, writer, cb);
            } else {
                cb.onLog(text(R.string.unknown_extension_detect));
                try {
                    if (password != null && !password.isEmpty()) extractZipWithPassword(ctx, temp, writer, password, cb);
                    else extractZip(temp, writer, cb);
                } catch (Exception e) {
                    if (cb.isCancelled()) throw e;
                    extractTar(temp, lower, writer, cb);
                }
            }
            cb.onLog(text(R.string.extract_all_done, name));
        } finally {
            temp.delete();
        }
    }

    private static void extractZip(File file, SafWriter writer, Callback cb) throws IOException {
        try (ZipFile zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            int total = zip.size();
            int count = 0;
            while (en.hasMoreElements()) {
                checkCancelled(cb);
                ZipEntry e = en.nextElement();
                String clean = SafWriter.sanitize(e.getName());
                if (clean.isEmpty()) continue;
                if (e.isDirectory()) {
                    writer.ensureDir(clean);
                } else {
                    try (InputStream in = zip.getInputStream(e); OutputStream out = writer.createFile(clean)) {
                        copy(in, out, cb);
                    }
                    count++;
                    cb.onProgress(clean, count, total);
                }
            }
            cb.onLog(text(R.string.files_extracted, count));
        }
    }

    /** Ekstrak ZIP berpassword (termasuk AES) lewat Zip4j: tampung lokal dulu, lalu salin ke folder tujuan. */
    private static void extractZipWithPassword(Context ctx, File file, SafWriter writer, String password, Callback cb) throws IOException {
        File dir = new File(ctx.getCacheDir(), "zipout_" + System.currentTimeMillis());
        if (!dir.exists()) dir.mkdirs();
        try {
            net.lingala.zip4j.ZipFile zf = new net.lingala.zip4j.ZipFile(file, password.toCharArray());
            zf.extractAll(dir.getAbsolutePath());
            List<File> files = new ArrayList<>();
            collectLocalFiles(dir, dir, files, cb);
            int count = 0;
            for (File f : files) {
                checkCancelled(cb);
                String rel = dir.toURI().relativize(f.toURI()).getPath();
                String clean = SafWriter.sanitize(rel);
                if (clean.isEmpty()) continue;
                try (InputStream in = new FileInputStream(f); OutputStream out = writer.createFile(clean)) {
                    copy(in, out, cb);
                }
                count++;
                cb.onProgress(clean, count, files.size());
            }
            cb.onLog(text(R.string.files_extracted, count));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void collectLocalFiles(File root, File dir, List<File> out, Callback cb) throws IOException {
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            checkCancelled(cb);
            if (f.isDirectory()) collectLocalFiles(root, f, out, cb);
            else out.add(f);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] arr = f.listFiles();
            if (arr != null) for (File child : arr) deleteRecursively(child);
        }
        f.delete();
    }

    private static void extract7z(File file, SafWriter writer, String password, Callback cb) throws IOException {
        SevenZFile.Builder builder = SevenZFile.builder().setFile(file);
        if (password != null && !password.isEmpty()) builder.setPassword(password.toCharArray());
        try (SevenZFile sevenZ = builder.get()) {
            SevenZArchiveEntry e;
            int count = 0;
            byte[] buf = new byte[BUF];
            while ((e = sevenZ.getNextEntry()) != null) {
                checkCancelled(cb);
                String clean = SafWriter.sanitize(e.getName());
                if (clean.isEmpty()) continue;
                if (e.isDirectory()) {
                    writer.ensureDir(clean);
                } else {
                    try (OutputStream out = writer.createFile(clean)) {
                        int n;
                        while ((n = sevenZ.read(buf)) != -1) {
                            checkCancelled(cb);
                            out.write(buf, 0, n);
                        }
                    }
                    count++;
                    cb.onProgress(clean, count, -1);
                }
            }
            cb.onLog(text(R.string.files_extracted, count));
        }
    }

    private static void extractRar(File file, SafWriter writer, String password, Callback cb) throws Exception {
        Archive archive;
        if (password != null && !password.isEmpty()) archive = new Archive(file, password);
        else archive = new Archive(file);
        try {
            FileHeader fh;
            int count = 0;
            while ((fh = archive.nextFileHeader()) != null) {
                checkCancelled(cb);
                String clean = SafWriter.sanitize(fh.getFileName());
                if (clean.isEmpty()) continue;
                if (fh.isDirectory()) {
                    writer.ensureDir(clean);
                } else {
                    try (OutputStream out = writer.createFile(clean)) {
                        archive.extractFile(fh, out);
                    }
                    count++;
                    cb.onProgress(clean, count, -1);
                }
            }
            cb.onLog(text(R.string.files_extracted, count));
        } finally {
            try { archive.close(); } catch (Exception ignored) {}
        }
    }

    private static void extractTar(File file, String lower, SafWriter writer, Callback cb) throws IOException {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            InputStream wrapped;
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) wrapped = new GzipCompressorInputStream(fis);
            else if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz")) wrapped = new BZip2CompressorInputStream(fis);
            else if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) wrapped = new XZCompressorInputStream(fis);
            else wrapped = fis;
            try (TarArchiveInputStream tar = new TarArchiveInputStream(wrapped, StandardCharsets.UTF_8.name())) {
                TarArchiveEntry e;
                int count = 0;
                while ((e = tar.getNextTarEntry()) != null) {
                    checkCancelled(cb);
                    String clean = SafWriter.sanitize(e.getName());
                    if (clean.isEmpty()) continue;
                    if (e.isDirectory()) {
                        writer.ensureDir(clean);
                    } else {
                        try (OutputStream out = writer.createFile(clean)) {
                            copy(tar, out, cb);
                        }
                        count++;
                        cb.onProgress(clean, count, -1);
                    }
                }
                cb.onLog(text(R.string.files_extracted, count));
            }
        }
    }

    private static void extractSingle(File file, String origName, String lower, SafWriter writer, Callback cb) throws IOException {
        String outName = origName;
        if (lower.endsWith(".gz") || lower.endsWith(".xz")) outName = origName.substring(0, origName.length() - 3);
        else if (lower.endsWith(".bz2")) outName = origName.substring(0, origName.length() - 4);
        if (outName.isEmpty()) outName = text(R.string.extracted_file_fallback);
        cb.onProgress(outName, 0, 1);
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            InputStream wrapped;
            if (lower.endsWith(".gz")) wrapped = new GzipCompressorInputStream(fis);
            else if (lower.endsWith(".bz2")) wrapped = new BZip2CompressorInputStream(fis);
            else wrapped = new XZCompressorInputStream(fis);
            try (OutputStream out = writer.createFile(outName)) {
                copy(wrapped, out, cb);
            }
        }
        cb.onProgress(outName, 1, 1);
        cb.onLog(text(R.string.one_file_extracted));
    }

    // ---------------- KOMPRES ----------------

    public static void compressItems(Context ctx, List<Item> items, Uri outputUri, String format, Callback cb) throws Exception {
        compressItems(ctx, items, outputUri, format, cb, "");
    }

    public static void compressItems(Context ctx, List<Item> items, Uri outputUri, String format, Callback cb, String password) throws Exception {
        resources = ctx.getResources();
        int totalFiles = 0;
        for (Item it : items) if (!it.dir) totalFiles++;
        cb.onLog(text(R.string.compressing_files, totalFiles, format, password != null && !password.isEmpty() ? text(R.string.with_password_suffix) : ""));
        if (totalFiles == 0) throw new IOException(text(R.string.empty_folder));
        if ("7Z".equals(format)) compress7z(ctx, items, outputUri, totalFiles, cb, password);
        else if (format.startsWith("TAR")) {
            if (password != null && !password.isEmpty()) throw new IOException(text(R.string.tar_password_engine_unsupported));
            compressTar(ctx, items, outputUri, format, totalFiles, cb);
        }
        else compressZip(ctx, items, outputUri, totalFiles, cb, password);
        cb.onProgress("", totalFiles, totalFiles);
        cb.onLog(text(R.string.archive_created));
    }

    /** Cara lama (daftar Uri file datar) — dipakai kompres file. */
    public static void compress(Context ctx, List<Uri> inputs, Uri outputUri, String format, Callback cb) throws Exception {
        compress(ctx, inputs, outputUri, format, cb, "");
    }

    public static void compress(Context ctx, List<Uri> inputs, Uri outputUri, String format, Callback cb, String password) throws Exception {
        List<Item> items = itemsFromFiles(ctx.getContentResolver(), inputs, cb);
        compressItems(ctx, items, outputUri, format, cb, password);
    }

    /** Kompres satu folder utuh (struktur tetap). */
    public static void compressFolder(Context ctx, Uri treeUri, Uri outputUri, String format, Callback cb) throws Exception {
        compressFolder(ctx, treeUri, outputUri, format, cb, false);
    }

    public static void compressFolder(Context ctx, Uri treeUri, Uri outputUri, String format, Callback cb, boolean includeHidden) throws Exception {
        compressFolder(ctx, treeUri, outputUri, format, cb, includeHidden, "");
    }

    public static void compressFolder(Context ctx, Uri treeUri, Uri outputUri, String format, Callback cb, boolean includeHidden, String password) throws Exception {
        List<Item> items = collectFolder(ctx, treeUri, cb, includeHidden);
        // Jaring pengaman: kalau user tetap naruh hasil arsip di DALAM folder sumber,
        // file hasil itu jangan ikut dikompres ke dalam dirinya sendiri.
        if (outputUri != null) {
            String outDocId = null;
            try { outDocId = DocumentsContract.getDocumentId(outputUri); } catch (Exception ignored) {}
            if (outDocId != null) {
                List<Item> filtered = new ArrayList<>();
                int removed = 0;
                for (Item it : items) {
                    String docId = null;
                    try { docId = DocumentsContract.getDocumentId(it.uri); } catch (Exception ignored) {}
                    if (!it.dir && outDocId.equals(docId)) { removed++; continue; }
                    filtered.add(it);
                }
                if (removed > 0) cb.onLog(text(R.string.output_inside_source));
                items = filtered;
            }
        }
        compressItems(ctx, items, outputUri, format, cb, password);
    }

    private static void compressZip(Context ctx, List<Item> items, Uri outputUri, int totalFiles, Callback cb) throws IOException {
        compressZip(ctx, items, outputUri, totalFiles, cb, "");
    }

    private static void compressZip(Context ctx, List<Item> items, Uri outputUri, int totalFiles, Callback cb, String password) throws IOException {
        if (password != null && !password.isEmpty()) {
            compressZipEncrypted(ctx, items, outputUri, totalFiles, cb, password);
            return;
        }
        ContentResolver resolver = ctx.getContentResolver();
        int done = 0;
        try (OutputStream raw = resolver.openOutputStream(outputUri, "w");
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw), StandardCharsets.UTF_8)) {
            for (Item it : items) {
                checkCancelled(cb);
                if (it.dir) {
                    String p = it.path.endsWith("/") ? it.path : it.path + "/";
                    zip.putNextEntry(new ZipEntry(p));
                    zip.closeEntry();
                } else {
                    cb.onProgress(it.path, done, totalFiles);
                    zip.putNextEntry(new ZipEntry(it.path));
                    try (InputStream in = resolver.openInputStream(it.uri)) {
                        if (in != null) copy(in, zip, cb);
                    }
                    zip.closeEntry();
                    done++;
                    cb.onProgress(it.path, done, totalFiles);
                }
            }
        }
    }

    /** ZIP AES-256 berpassword lewat Zip4j: bikin ke file sementara, baru salin ke lokasi tujuan. */
    private static void compressZipEncrypted(Context ctx, List<Item> items, Uri outputUri, int totalFiles, Callback cb, String password) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        File dir = new File(ctx.getCacheDir(), "work");
        if (!dir.exists()) dir.mkdirs();
        File tmp = new File(dir, "out_" + System.currentTimeMillis() + ".zip");
        int done = 0;
        try {
            net.lingala.zip4j.ZipFile zipFile = new net.lingala.zip4j.ZipFile(tmp, password.toCharArray());
            for (Item it : items) {
                checkCancelled(cb);
                net.lingala.zip4j.model.ZipParameters params = new net.lingala.zip4j.model.ZipParameters();
                params.setEncryptFiles(true);
                params.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
                params.setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256);
                if (it.dir) {
                    String p = it.path.endsWith("/") ? it.path : it.path + "/";
                    params.setFileNameInZip(p);
                    zipFile.addStream(new ByteArrayInputStream(new byte[0]), params);
                    continue;
                }
                cb.onProgress(it.path, done, totalFiles);
                params.setFileNameInZip(it.path);
                try (InputStream in = resolver.openInputStream(it.uri)) {
                    if (in != null) {
                        // Zip4j membaca stream sampai habis; pemeriksaan batal dilakukan antar file.
                        zipFile.addStream(in, params);
                    }
                }
                done++;
                cb.onProgress(it.path, done, totalFiles);
            }
            zipFile.close();
            try (InputStream in = new FileInputStream(tmp); OutputStream out = resolver.openOutputStream(outputUri, "w")) {
                if (out == null) throw new IOException(text(R.string.cannot_write_output));
                copy(in, out, cb);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            tmp.delete();
        }
    }

    private static void compressTar(Context ctx, List<Item> items, Uri outputUri, String format, int totalFiles, Callback cb) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        int done = 0;
        try (OutputStream raw = resolver.openOutputStream(outputUri, "w")) {
            OutputStream wrapped;
            if ("TAR.GZ".equals(format)) wrapped = new GzipCompressorOutputStream(new BufferedOutputStream(raw));
            else if ("TAR.BZ2".equals(format)) wrapped = new BZip2CompressorOutputStream(new BufferedOutputStream(raw));
            else if ("TAR.XZ".equals(format)) wrapped = new XZCompressorOutputStream(new BufferedOutputStream(raw));
            else wrapped = new BufferedOutputStream(raw);

            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(wrapped, StandardCharsets.UTF_8.name())) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                for (Item it : items) {
                    checkCancelled(cb);
                    if (it.dir) {
                        String p = it.path.endsWith("/") ? it.path : it.path + "/";
                        TarArchiveEntry entry = new TarArchiveEntry(p);
                        entry.setSize(0);
                        tar.putArchiveEntry(entry);
                        tar.closeArchiveEntry();
                        continue;
                    }
                    cb.onProgress(it.path, done, totalFiles);
                    // TAR nulis ukuran di header SEBELUM isi file — jadi ukurannya harus pasti.
                    // SAF kadang bohong: ada file yang dilaporin 0 byte padahal isinya ada
                    // (kasus «Telegram Video.tar.gz» di v1.1: header 0, lalu nulis 8192 -> meledak).
                    // Kalau ukuran dari SAF <= 0 atau beda sama hasil stat file descriptor,
                    // tampung dulu ke file sementara dan ukur dari situ. Pasti bener.
                    long reported = it.size;
                    long stat = getStatSize(resolver, it.uri);
                    boolean sizeTrustworthy = reported > 0 && (stat < 0 || stat == reported);
                    File tmp = null;
                    InputStream in = resolver.openInputStream(it.uri);
                    if (in == null) continue;
                    long size;
                    if (sizeTrustworthy) {
                        size = reported;
                    } else {
                        File dir = new File(ctx.getCacheDir(), "work");
                        if (!dir.exists()) dir.mkdirs();
                        tmp = new File(dir, "tz_" + System.currentTimeMillis() + "_" + done + ".tmp");
                        try (OutputStream tout = new FileOutputStream(tmp)) { copy(in, tout, cb); }
                        in.close();
                        size = tmp.length();
                        in = new FileInputStream(tmp);
                    }
                    TarArchiveEntry entry = new TarArchiveEntry(it.path);
                    entry.setSize(size);
                    tar.putArchiveEntry(entry);
                    try (InputStream src = in) {
                        long written = copy(src, tar, cb);
                        if (written != size) {
                            throw new IOException(text(R.string.file_size_changed, it.path, written, size));
                        }
                    }
                    tar.closeArchiveEntry();
                    if (tmp != null) tmp.delete();
                    done++;
                    cb.onProgress(it.path, done, totalFiles);
                }
            }
        }
    }

    private static void compress7z(Context ctx, List<Item> items, Uri outputUri, int totalFiles, Callback cb) throws IOException {
        compress7z(ctx, items, outputUri, totalFiles, cb, "");
    }

    private static void compress7z(Context ctx, List<Item> items, Uri outputUri, int totalFiles, Callback cb, String password) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        File dir = new File(ctx.getCacheDir(), "work");
        if (!dir.exists()) dir.mkdirs();
        File emptyDir = new File(dir, "emptydir");
        if (!emptyDir.exists()) emptyDir.mkdirs();
        File tmp = new File(dir, "out_" + System.currentTimeMillis() + ".7z");
        int done = 0;
        try {
            SevenZOutputFile sevenZ = (password != null && !password.isEmpty())
                    ? new SevenZOutputFile(tmp, password.toCharArray())
                    : new SevenZOutputFile(tmp);
            try {
                for (Item it : items) {
                    checkCancelled(cb);
                    if (it.dir) {
                        SevenZArchiveEntry entry = sevenZ.createArchiveEntry(emptyDir, it.path);
                        sevenZ.putArchiveEntry(entry);
                        sevenZ.closeArchiveEntry();
                        continue;
                    }
                    cb.onProgress(it.path, done, totalFiles);
                    String safe = it.path.replaceAll("[^a-zA-Z0-9._/-]", "_");
                    File inTmp = new File(dir, "in7_" + System.currentTimeMillis() + "_" + safe.substring(safe.lastIndexOf('/') + 1));
                    try (InputStream in = resolver.openInputStream(it.uri); OutputStream out = new FileOutputStream(inTmp)) {
                        if (in != null) copy(in, out, cb);
                    }
                    SevenZArchiveEntry entry = sevenZ.createArchiveEntry(inTmp, it.path);
                    sevenZ.putArchiveEntry(entry);
                    try (InputStream in = new FileInputStream(inTmp)) {
                        byte[] buf = new byte[BUF];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            checkCancelled(cb);
                            sevenZ.write(buf, 0, n);
                        }
                    }
                    sevenZ.closeArchiveEntry();
                    inTmp.delete();
                    done++;
                    cb.onProgress(it.path, done, totalFiles);
                }
            } finally {
                sevenZ.close();
            }
            try (InputStream in = new FileInputStream(tmp); OutputStream out = resolver.openOutputStream(outputUri, "w")) {
                copy(in, out, cb);
            }
        } finally {
            tmp.delete();
        }
    }
}
