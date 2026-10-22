import com.tlsu.oshareairdrop.DiscoveryChannels;
import java.util.List;

public final class DiscoveryChannelsTest {
    public static void main(String[] args) {
        List<Integer> fallback = List.of(6);
        for (String mode : List.of("", "coexist", "wrong"))
            check(DiscoveryChannels.select(mode,5745,true,fallback) == fallback);
        check(DiscoveryChannels.select("same149",5745,true,fallback).equals(List.of(149)));
        check(DiscoveryChannels.select("cross5g",5200,true,fallback).equals(List.of(149)));
        for (int freq : new int[]{2412,2447,2484,5180,5200,5500,5745,5805,5925,5955,7115,7125}) {
            check(DiscoveryChannels.select("auto",freq,true,fallback).equals(List.of(149)));
            check(DiscoveryChannels.select("auto",freq,false,fallback) == fallback);
        }
        for (int freq : new int[]{0,2399,2485,5149,7126,9000})
            check(DiscoveryChannels.select("auto",freq,true,fallback) == fallback);
        check(DiscoveryChannels.select("auto",5805,true,List.of(6,44)).equals(List.of(149)));
        check(DiscoveryChannels.select("auto",5805,true,List.of()).isEmpty());
        check(DiscoveryChannels.select("auto",5805,true,null) == null);
        for (int freq : new int[]{2447,5180,0}) {
            check(DiscoveryChannels.select("same149",freq,true,fallback) == fallback);
            check(DiscoveryChannels.select("cross5g",freq,true,fallback) == fallback);
        }
        check(DiscoveryChannels.select("same149",5745,false,fallback) == fallback);
        check(DiscoveryChannels.select("same149",5745,true,List.of()).isEmpty());
        check(DiscoveryChannels.select("same149",5745,true,null) == null);
        System.out.println("DiscoveryChannels: fixed149 fallback, ownership, STA frequency and disconnect guards passed");
    }
    private static void check(boolean v) { if (!v) throw new AssertionError(); }
}
