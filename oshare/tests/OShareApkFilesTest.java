package com.tlsu.oshareairdrop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class OShareApkFilesTest {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static void apk(Path path, String... names) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("oshare-apk-test-");
        try {
            Path base = dir.resolve("base.apk"), split = dir.resolve("split.apk");
            apk(base, "classes.dex", "classes2.dex", "assets/classes.dex");
            apk(split, "resources.arsc");
            List<String> paths = List.of(base.toString(), split.toString());
            String identity = OShareApkFiles.identity(paths, 1, 1);
            check(identity.equals(OShareApkFiles.identity(paths, 1, 1)), "stable cache key");
            check(!identity.equals(OShareApkFiles.identity(paths, 2, 1)), "rule schema invalidation");
            check(!identity.equals(OShareApkFiles.identity(paths, 1, 2)), "module update invalidation");
            byte[][] dex = OShareApkFiles.readDexFiles(paths);
            check(dex.length == 2, "only root DEX files; resource split ignored");
            check(Arrays.equals(dex[0], "classes.dex".getBytes()), "DEX bytes retained");
            apk(split, "classes.dex");
            check(!identity.equals(OShareApkFiles.identity(paths, 1, 1)), "code split content invalidation");
            check(OShareApkFiles.readDexFiles(paths).length == 3, "code split included");
            String before = OShareApkFiles.identity(paths, 1, 1);
            java.nio.file.attribute.FileTime timestamp = Files.getLastModifiedTime(base);
            byte[] original = Files.readAllBytes(base);
            byte[] changed = original.clone();
            changed[changed.length - 1] ^= 1;
            Files.write(base, changed);
            Files.setLastModifiedTime(base, timestamp);
            check(!before.equals(OShareApkFiles.identity(paths, 1, 1)), "same size and timestamp must invalidate");
            Files.write(base, original);
            apk(split, "resources.arsc");
            try { OShareApkFiles.readDexFiles(List.of(split.toString())); throw new AssertionError("empty DEX accepted"); }
            catch (IllegalStateException expected) { /* unsupported package */ }
            System.out.println("OShare APK inputs: multidex, splits, content/schema/module cache invalidation passed");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
