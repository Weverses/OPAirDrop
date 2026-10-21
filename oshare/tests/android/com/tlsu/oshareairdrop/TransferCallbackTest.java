package com.tlsu.oshareairdrop;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;
import java.util.ArrayList;
import org.json.JSONObject;

/** Runs the actual ProviderApi callback/JSON/file cleanup code on Android without a radio session. */
public final class TransferCallbackTest {
    public static class Target {
        public long a = 7;
        public String b = "test", g = "", p = "", l = "";
        public int d = 3;
        public boolean f = false;
    }
    public static class Metadata {
        public int a;
        public double b = Double.NaN;
        public boolean e;
        public long h = 1, s = 0;
        Metadata(int status) { a = status; }
    }
    public static final class PartialMetadata {
        public int a = 1006;
        public double b = 100;
        public boolean e = true;
        public long h = 1;
        // s intentionally absent: parsing this optional metadata must not retain the task.
    }
    static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        Field outgoingField = ProviderApi.class.getDeclaredField("OUTGOING");
        outgoingField.setAccessible(true);
        OutgoingTransfers<File> outgoing = (OutgoingTransfers<File>) outgoingField.get(null);
        Method callback = ProviderApi.class.getDeclaredMethod("handleTransferCallback",
                String.class, Object[].class, OutgoingTransfers.Lease.class);
        callback.setAccessible(true);
        ArrayList<JSONObject> events = new ArrayList<>();
        ProviderApi.setSink(events::add);
        File base = new File(args[0]);
        check(base.mkdirs(), "isolated test folder exists");
        Target target = new Target();
        try {
            for (int status : new int[]{1006,1007,1008,1009,1010,1018}) {
                File folder = new File(base, "status-" + status);
                check(folder.mkdirs(), "cache folder");
                Files.write(new File(folder,"sample.txt").toPath(), new byte[]{1,2,3});
                OutgoingTransfers.Lease<File> lease = outgoing.begin(7, folder);
                callback.invoke(null, "a", new Object[]{target,new Metadata(1003)}, lease);
                check(outgoing.isCurrent(lease), "waiting prematurely released ownership");
                callback.invoke(null, "a", new Object[]{target,new Metadata(status)}, lease);
                check(!outgoing.isActive(7), "terminal retained lease: " + status);
                check(!folder.exists(), "terminal retained files: " + status);
                JSONObject last = events.get(events.size()-1);
                check(last.getBoolean("terminal"), "missing normalized terminal flag");
                check(last.getDouble("progress") == 0, "NaN was not normalized");
                OutgoingTransfers.Lease<File> retry = outgoing.begin(7, null);
                callback.invoke(null, "a", new Object[]{target,new Metadata(status)}, lease);
                check(outgoing.isCurrent(retry), "old callback removed retry");
                outgoing.finish(retry);
            }
            target.d = 0;
            ProviderApi.rememberTargetType(7, 1);
            ProviderApi.rememberTargetType(7, 0); // Later incomplete metadata must not erase PHONE.
            OutgoingTransfers.Lease<File> typed = outgoing.begin(7, null);
            callback.invoke(null,"a",new Object[]{target,new Metadata(1006)},typed);
            check(events.get(events.size()-1).getInt("type") == 1, "known PHONE lost in callback JSON");
            File partial = new File(base,"partial-metadata");
            check(partial.mkdirs(), "partial folder");
            OutgoingTransfers.Lease<File> lease = outgoing.begin(7,partial);
            callback.invoke(null,"a",new Object[]{target,new PartialMetadata()},lease);
            check(!outgoing.isActive(7) && !partial.exists(), "optional-field exception skipped cleanup");
            System.out.println("ANDROID_CALLBACK_REGRESSION_PASSED: waiting, 6 terminal statuses, NaN, stale callbacks, partial metadata, cache cleanup");
        } finally {
            base.delete();
        }
    }
}
