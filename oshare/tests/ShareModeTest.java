package com.tlsu.oshareairdrop;

public final class ShareModeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(ShareMode.parse(null) == ShareMode.BOTH, "unset mode changed existing behavior");
        check(ShareMode.parse("invalid") == ShareMode.BOTH, "invalid mode changed existing behavior");
        check(!ShareMode.parse("quickshare").usesOShare(), "Quick Share still owns OShare");
        for (String method : new String[]{"g", "k", "f", "j", "d", "a", "b", "c", "i", "h", "m"}) {
            check(ShareMode.BOTH.nativeResult(method) == null, "coexistence blocked native " + method);
            check(ShareMode.QUICKSHARE.nativeResult(method) == null, "Quick Share blocked native " + method);
            check(ShareMode.OSHARE.nativeResult(method) != null, "OShare allowed native " + method);
            ShareMode.bridgeCall(() -> {
                check(ShareMode.OSHARE.nativeResult(method) == null, "bridge call blocked " + method);
                ShareMode.bridgeCall(() -> null);
                check(ShareMode.OSHARE.nativeResult(method) == null, "nested bridge marker lost");
                return null;
            });
            check(ShareMode.OSHARE.nativeResult(method) != null, "bridge marker leaked");
        }
        check(ShareMode.OSHARE.nativeResult("d") != 0, "disabled send reported success");
        check(ShareMode.OSHARE.nativeResult("h") == 0, "native stop could stop bridge advertising");
        try { ShareMode.bridgeCall(() -> { throw new Exception("expected"); }); }
        catch (Exception expected) {}
        check(ShareMode.OSHARE.nativeResult("d") != null, "failed call leaked bridge marker");
        check(ShareMode.OSHARE.nativeResult("unrelated") == null, "unrelated operation blocked");
        System.out.println("ShareMode passed: isolation, bridge ownership, nested calls and exception cleanup");
    }
}
