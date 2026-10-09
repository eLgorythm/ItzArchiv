package me.fndlabs.itzarchiv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class SafWriter {
    private final ContentResolver resolver;
    private final Resources resources;
    private final Uri treeUri;
    private final String rootDocId;
    private final Map<String, String> dirCache = new HashMap<>();

    public SafWriter(Context ctx, Uri treeUri) {
        this.resolver = ctx.getContentResolver();
        this.resources = ctx.getResources();
        this.treeUri = treeUri;
        this.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        dirCache.put("", rootDocId);
    }

    public String ensureDir(String relPath) throws IOException {
        if (relPath == null || relPath.isEmpty() || relPath.equals(".")) return rootDocId;
        String normalized = relPath.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) return rootDocId;
        if (dirCache.containsKey(normalized)) return dirCache.get(normalized);

        String[] parts = normalized.split("/");
        String curPath = "";
        String curDoc = rootDocId;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            curPath = curPath.isEmpty() ? part : curPath + "/" + part;
            if (dirCache.containsKey(curPath)) {
                curDoc = dirCache.get(curPath);
                continue;
            }
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, curDoc);
            Uri created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, part);
            if (created == null) throw new IOException(resources.getString(R.string.failed_create_folder, part));
            String createdId = DocumentsContract.getDocumentId(created);
            dirCache.put(curPath, createdId);
            curDoc = createdId;
        }
        return curDoc;
    }

    public OutputStream createFile(String relPath) throws IOException {
        String normalized = relPath.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        int slash = normalized.lastIndexOf('/');
        String dir = slash >= 0 ? normalized.substring(0, slash) : "";
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (name.isEmpty() || name.equals(".") || name.equals("..")) throw new IOException(resources.getString(R.string.invalid_file_name, relPath));
        String dirDoc = ensureDir(dir);
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDoc);
        String mime = guessMime(name);
        Uri created = DocumentsContract.createDocument(resolver, parentUri, mime, name);
        if (created == null) throw new IOException(resources.getString(R.string.failed_create_file, name));
        OutputStream out = resolver.openOutputStream(created, "w");
        if (out == null) throw new IOException(resources.getString(R.string.failed_open_output_file, name));
        return out;
    }

    public static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }

    public static String sanitize(String entryName) {
        if (entryName == null) return "";
        String s = entryName.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        // buang komponen .. biar gak kabur dari folder tujuan (zip slip)
        String[] parts = s.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }
}
