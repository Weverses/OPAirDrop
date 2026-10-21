package com.tlsu.oshareairdrop;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Content identity and multidex input shared by runtime resolution and offline tests. */
final class OShareApkFiles {
    private OShareApkFiles() {}

    static String identity(List<String> paths, int schema, int moduleVersion) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("schema=" + schema + ":module=" + moduleVersion).getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[65536];
        for (String path : paths) {
            digest.update(("\0path=" + path + "\0").getBytes(StandardCharsets.UTF_8));
            try (InputStream input = Files.newInputStream(Path.of(path))) {
                for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    static byte[][] readDexFiles(List<String> paths) throws Exception {
        List<byte[]> files = new ArrayList<>();
        for (String path : paths) try (ZipFile apk = new ZipFile(path)) {
            java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().matches("classes(?:[0-9]+)?\\.dex"))
                    try (InputStream input = apk.getInputStream(entry)) { files.add(input.readAllBytes()); }
            }
        }
        if (files.isEmpty()) throw new IllegalStateException("OSHARE_DEX_UNSUPPORTED: no DEX inputs");
        return files.toArray(new byte[0][]);
    }
}
