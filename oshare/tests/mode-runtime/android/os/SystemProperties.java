package android.os;
import java.util.concurrent.ConcurrentHashMap;
public class SystemProperties {
 private static final ConcurrentHashMap<String,String> VALUES = new ConcurrentHashMap<>();
 public static String get(String key,String fallback) { return VALUES.getOrDefault(key,fallback); }
 public static void set(String key,String value) { VALUES.put(key,value); }
}
