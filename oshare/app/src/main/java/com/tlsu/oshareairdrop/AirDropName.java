package com.tlsu.oshareairdrop;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Shared persisted representation: at most 64 Unicode scalar values, UTF-8 hex. */
public final class AirDropName {
    public static final String SETTING = "opairshare_airdrop_name_hex";
    private AirDropName() {}

    public static String decode(String hex) {
        try {
            if (hex == null || hex.isEmpty() || hex.length() > 512 || hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+"))
                return "";
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int a = Character.digit(hex.charAt(i * 2), 16), b = Character.digit(hex.charAt(i * 2 + 1), 16);
                if (a < 0 || b < 0) return "";
                bytes[i] = (byte) (a * 16 + b);
            }
            String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (name.codePointCount(0, name.length()) > 64 || name.codePoints().anyMatch(cp ->
                    Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029)) return "";
            if (name.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xfeff)) return "";
            return name;
        } catch (Exception invalid) { return ""; }
    }

    public static String resolve(String storedHex, String marketName, String model) {
        String custom = decode(storedHex);
        if (!custom.isEmpty()) return custom;
        if (marketName != null && !marketName.isEmpty()) return marketName;
        return model == null || model.isEmpty() ? "OPAirShare" : model;
    }
}
