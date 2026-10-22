package com.tlsu.oshareairdrop;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Public shared-storage destination; empty preserves the original per-type folders. */
public final class ReceiveDirectory {
    public static final String SETTING = "opairshare_receive_directory_hex";
    private ReceiveDirectory() {}

    public static String decode(String hex) {
        try {
            if (hex == null || hex.isEmpty() || hex.length() > 1024 || hex.length() % 2 != 0
                    || !hex.matches("[0-9a-fA-F]+")) return "";
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            String path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return valid(path) ? path : "";
        } catch (Exception invalid) { return ""; }
    }

    public static boolean valid(String path) {
        if (path == null || path.getBytes(StandardCharsets.UTF_8).length > 512
                || path.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029
                || (cp >= 0xd800 && cp <= 0xdfff))) return false;
        String[] parts = path.split("/", -1);
        if (!parts[0].equals("Download") && !parts[0].equals("Documents")) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".") || !part.equals(part.trim())
                    || part.matches(".*[<>:\"\\\\|?*].*") || part.getBytes(StandardCharsets.UTF_8).length > 200) return false;
        }
        return true;
    }
}
