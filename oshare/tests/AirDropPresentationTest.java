import com.tlsu.oshareairdrop.AirDropPresentation;
import com.tlsu.oshareairdrop.AirDropPresentation.Kind;

public final class AirDropPresentationTest {
    static void expect(Kind actual, Kind expected) { if (actual != expected) throw new AssertionError(actual + " != " + expected); }
    public static void main(String[] args) {
        if (!"IDLE".equals(AirDropPresentation.DISCOVERED_STATE)) throw new AssertionError("discovery must not enter sending state");
        if (!"等待对方接收".equals(AirDropPresentation.pendingLabel(1003))) throw new AssertionError("receiver confirmation label");
        if (AirDropPresentation.pendingLabel(1005) != null) throw new AssertionError("native transfer label must take over");
        expect(AirDropPresentation.preferKnown(Kind.PHONE, Kind.UNKNOWN), Kind.PHONE);
        expect(AirDropPresentation.preferKnown(Kind.PHONE, Kind.TABLET), Kind.TABLET);
        expect(AirDropPresentation.preferKnown(null, Kind.UNKNOWN), Kind.UNKNOWN);
        expect(AirDropPresentation.kind(1, "", "Alice"), Kind.PHONE);
        expect(AirDropPresentation.kind(2, "", "Tablet"), Kind.TABLET);
        expect(AirDropPresentation.kind(3, "", "MacBook Pro"), Kind.LAPTOP);
        expect(AirDropPresentation.kind(3, "Macmini9,1", "Office"), Kind.DESKTOP);
        expect(AirDropPresentation.kind(0, "", "iMac"), Kind.DESKTOP);
        expect(AirDropPresentation.kind(0, "iPad14,3", "Alice"), Kind.TABLET);
        expect(AirDropPresentation.kind(1, "", "MacBook's iPhone"), Kind.PHONE);
        expect(AirDropPresentation.kind(4, "", "Car"), Kind.UNKNOWN); // 4 is CAR, not desktop.
        expect(AirDropPresentation.kind(6, "", "XR"), Kind.UNKNOWN);
        expect(AirDropPresentation.kind(0, null, null), Kind.UNKNOWN);
        if (!"TRANSITING".equals(AirDropPresentation.state(1005,false))) throw new AssertionError("progress state");
        if (!"TRANSIT_WAIT".equals(AirDropPresentation.state(1003,false))) throw new AssertionError("waiting state");
        if (!"TRANSIT_SUCCESS".equals(AirDropPresentation.state(1006,true))) throw new AssertionError("success state");
        if (!"TRANSIT_FAILED".equals(AirDropPresentation.state(1007,false))) throw new AssertionError("terminal state");
        System.out.println("AirDrop presentation: phone/tablet/laptop/desktop/unknown icons and native transfer states passed");
    }
}
