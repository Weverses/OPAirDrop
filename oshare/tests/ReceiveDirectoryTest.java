package com.tlsu.oshareairdrop;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class ReceiveDirectoryTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        for (String path : new String[]{"Download", "Documents/AirDrop", "Download/接收 📱/文件", "Download/$(touch nope); `id`"}) {
            check(ReceiveDirectory.valid(path));
            check(ReceiveDirectory.decode(HexFormat.of().formatHex(path.getBytes(StandardCharsets.UTF_8))).equals(path));
        }
        for (String path : new String[]{"", "/sdcard/Download", "Android/data", "Download/../secret", "Download//x", "Download/.OPAirShare-incoming",
                "Download/x/", "Download/x.", "Download/ x", "Download/x ", "Download/a\\b", "Download/a:b", "Download/a\nb", "Download/\u0080", "Download/\u2028", "Download/\ud800",
                "Download/" + "a".repeat(201), "Download/" + ("a".repeat(180) + "/").repeat(3) + "x"}) check(!ReceiveDirectory.valid(path));
        for (String hex : new String[]{"00", "a", "zz", "c0af", "eda080", "f4908080", null}) check(ReceiveDirectory.decode(hex).isEmpty());
        System.out.println("Receive directory: Unicode, shared folders, byte limits, UTF-8 and traversal checks passed");
    }
}
