import com.tlsu.oshareairdrop.AirDropName;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class AirDropNameTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String name = "一加 13 📱 ' $HOME $(id)";
        String hex = HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8));
        check(AirDropName.decode(hex).equals(name));
        check(AirDropName.resolve(hex, "OnePlus 13", "PJZ110").equals(name));
        check(AirDropName.resolve(null, "OnePlus 13", "PJZ110").equals("OnePlus 13"));
        check(AirDropName.resolve("bad", "", "PJZ110").equals("PJZ110"));
        check(AirDropName.resolve("", null, null).equals("OPAirShare"));
        for (String bad : new String[]{"0", "gg", "c0af", "eda080", "f4908080", "00", "0a", "c280", "e280a8", "20", "e38080"})
            check(AirDropName.decode(bad).isEmpty());
        check(!AirDropName.decode(HexFormat.of().formatHex("📱".repeat(64).getBytes(StandardCharsets.UTF_8))).isEmpty());
        check(AirDropName.decode(HexFormat.of().formatHex("a".repeat(65).getBytes(StandardCharsets.UTF_8))).isEmpty());
        System.out.println("AirDropName: default/fallback, UTF-8, Unicode limits and invalid input passed");
    }
}
