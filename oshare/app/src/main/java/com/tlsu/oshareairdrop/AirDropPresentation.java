package com.tlsu.oshareairdrop;

import java.util.Locale;

/** ShareTarget types are not the same enum as OShare's device types. */
public final class AirDropPresentation {
    public static final String DISCOVERED_STATE = "IDLE";
    public static final long CLICK_DEBOUNCE_MS = 1200;

    public static String pendingLabel(int status) {
        switch (status) {
            case 1001: return "正在连接对方";
            case 1002: return "等待本机确认";
            case 1003: return "等待对方接收";
            default: return null;
        }
    }

    public enum Kind {
        PHONE(1, "oshare_icon_apple"),
        TABLET(2, "oshare_icon_apple_pad"),
        LAPTOP(3, "oshare_icon_apple_macbook"),
        DESKTOP(4, "oshare_icon_apple_imac"),
        UNKNOWN(0, "oshare_icon_default");
        public final int oshareType;
        public final String drawable;
        Kind(int oshareType, String drawable) { this.oshareType = oshareType; this.drawable = drawable; }
    }
    private AirDropPresentation() {}

    public static Kind kind(int shareTargetType, String model, String name) {
        if (shareTargetType == 1) return Kind.PHONE;
        if (shareTargetType == 2) return Kind.TABLET;
        String hint = ((model == null ? "" : model) + " " + (name == null ? "" : name))
                .toLowerCase(Locale.ROOT);
        if (shareTargetType == 3 || shareTargetType == 0) {
            if (hint.contains("imac") || hint.contains("mac mini") || hint.contains("macmini")
                    || hint.contains("mac studio") || hint.contains("macstudio")
                    || hint.contains("mac pro") || hint.contains("macpro")) return Kind.DESKTOP;
            if (shareTargetType == 3 || hint.contains("macbook")) return Kind.LAPTOP;
        }
        if (shareTargetType == 0) {
            if (hint.contains("ipad")) return Kind.TABLET;
            if (hint.contains("iphone")) return Kind.PHONE;
        }
        return Kind.UNKNOWN;
    }

    public static Kind preferKnown(Kind previous, Kind candidate) {
        return candidate == Kind.UNKNOWN && previous != null ? previous : candidate;
    }

    public static String state(int status, boolean finalFlag) {
        switch (status) {
            case 1005: return "TRANSITING";
            case 1006: return "TRANSIT_SUCCESS";
            case 1008: return "TRANSIT_REJECT";
            case 1009: case 1018: return "CANCEL";
            case 1010: return "TRANSIT_TIMEOUT";
            case 1013: return "SPACE_NOT_ENOUGH";
            default: return OutgoingTransfers.isTerminal(status, finalFlag) ? "TRANSIT_FAILED" : "TRANSIT_WAIT";
        }
    }
}
