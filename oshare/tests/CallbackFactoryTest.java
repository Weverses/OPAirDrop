import com.tlsu.oshareairdrop.CallbackFactory;

/** Regression: aps/aqa are Java interfaces and do not extend IInterface. */
public class CallbackFactoryTest {
    public interface Discovery { void a(String target); }
    public static void main(String[] args) throws Exception {
        StringBuilder seen = new StringBuilder();
        Object callback = CallbackFactory.create(Discovery.class.getClassLoader(),
                Discovery.class.getName(), (method, values) -> {
                    seen.append(method).append(":").append(values[0]); return null;
                });
        ((Discovery) callback).a("Mac");
        if (!"a:Mac".contentEquals(seen)) throw new AssertionError(seen);
        if (!callback.equals(callback) || callback.equals(null)) throw new AssertionError("identity");
        if (callback.hashCode() != System.identityHashCode(callback)) throw new AssertionError("hash");
        System.out.println("CallbackFactory regression passed");
    }
}
