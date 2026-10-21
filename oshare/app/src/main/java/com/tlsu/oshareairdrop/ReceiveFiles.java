package com.tlsu.oshareairdrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Publishes only completed file attachments, keeping partial MediaStore rows hidden. */
final class ReceiveFiles {
    static final String DIRECTORY = ".OPAirShare-incoming";
    private ReceiveFiles() {}

    static JSONArray attachments(Object target) throws Exception {
        JSONArray files = new JSONArray();
        List<?> attachments = (List<?>) target.getClass().getMethod("d").invoke(target);
        if (attachments.isEmpty() || attachments.size() > 256)
            throw new IllegalArgumentException("仅支持 1 至 256 个文件");
        for (Object attachment : attachments) {
            if (!attachment.getClass().getName().equals("com.google.android.gms.nearby.sharing.FileAttachment"))
                throw new IllegalArgumentException("暂不支持文本、网址或文件夹接收");
            Object uri = field(attachment, "d");
            files.put(new JSONObject().put("attachmentId", ((Number) field(attachment, "g")).longValue()).put("name", safeName(String.valueOf(field(attachment, "a"))))
                    .put("size", -1).put("estimatedSize", ((Number) field(attachment, "c")).longValue())
                    .put("mime", mime((String) field(attachment, "f")))
                    .put("source", uri == null ? "" : uri.toString()));
        }
        return files;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static File staging(Context context) throws Exception {
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建接收暂存目录");
        return root.getCanonicalFile();
    }

    static String safeName(String name) {
        String value = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "AirDrop file";
        // Keep filenames within common filesystem byte limits, preserving code points.
        while (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 220)
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        return value;
    }

    private static String mime(String value) {
        return value != null && value.matches("[\\w.+-]+/[\\w.+-]+")
                ? value : "application/octet-stream";
    }

    static JSONArray publish(Context context, JSONArray files) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        List<Uri> created = new ArrayList<>();
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String type = mime(file.optString("mime"));
                Uri collection = type.startsWith("image/") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : type.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String folder = type.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                        : type.startsWith("video/") ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(file.getString("name")));
                values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/OPAirShare");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri destination = resolver.insert(collection, values);
                if (destination == null) throw new IllegalStateException("无法创建保存文件");
                created.add(destination);
                long bytes = 0;
                long expected;
                try (Source source = openSource(context, file);
                     OutputStream out = resolver.openOutputStream(destination, "w")) {
                    InputStream in = source.input;
                    expected = source.size;
                    if (in == null || out == null) throw new IllegalStateException("无法读取已接收文件");
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = in.read(buffer)) != -1;) {
                        out.write(buffer, 0, count);
                        bytes = Math.addExact(bytes, count);
                    }
                }
                if (expected >= 0 && bytes != expected) throw new IllegalStateException("文件大小不一致：" + file.getString("name"));
                result.put(new JSONObject().put("name", file.getString("name")).put("size", bytes)
                        .put("mime", type).put("uri", destination.toString()));
            }
            // Commit only after the entire batch has been copied and checked.
            for (Uri uri : created) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) != 1) throw new IllegalStateException("保存文件失败");
                context.grantUriPermission("com.coloros.oshare", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            cleanupStaging(context);
            return result;
        } catch (Exception failure) {
            for (Uri uri : created) try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            throw failure;
        }
    }

    private static final class Source implements AutoCloseable {
        final InputStream input;
        final long size;
        final android.content.res.AssetFileDescriptor descriptor;
        Source(InputStream input, long size, android.content.res.AssetFileDescriptor descriptor) {
            this.input = input; this.size = size; this.descriptor = descriptor;
        }
        @Override public void close() throws Exception {
            try { input.close(); } finally { if (descriptor != null) descriptor.close(); }
        }
    }

    private static void cleanupStaging(Context context) {
        try {
            File root = staging(context);
            File[] files = root.listFiles();
            if (files != null) for (File file : files)
                if (file.isFile() && file.getCanonicalFile().getParentFile().equals(root)) file.delete();
        } catch (Exception ignored) { /* Cleanup never changes a successful publication. */ }
    }

    private static Source openSource(Context context, JSONObject file) throws Exception {
        String source = file.optString("source");
        Uri uri = source.isEmpty() ? null : Uri.parse(source);
        if (uri != null && "content".equals(uri.getScheme())) {
            String authority = uri.getAuthority();
            if (!"media".equals(authority) && (authority == null || !authority.equals("com.google.android.mosey.fileprovider")))
                throw new SecurityException("非接收文件来源");
            android.content.res.AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (descriptor == null) throw new IllegalStateException("接收文件无法读取");
            try { return new Source(descriptor.createInputStream(), descriptor.getLength(), descriptor); }
            catch (Exception failure) { descriptor.close(); throw failure; }
        }
        File root = staging(context);
        File path = uri != null && "file".equals(uri.getScheme())
                ? new File(uri.getPath()).getCanonicalFile()
                : new File(root, safeName(file.getString("name"))).getCanonicalFile();
        if (!path.getPath().startsWith(root.getPath() + File.separator) || !path.isFile())
            throw new SecurityException("接收文件不在暂存目录内");
        return new Source(new FileInputStream(path), path.length(), null);
    }
}
