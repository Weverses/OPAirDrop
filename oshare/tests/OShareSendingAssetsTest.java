package com.tlsu.oshareairdrop;

import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Validates the real native page and refuses altered or unscoped actions. */
public final class OShareSendingAssetsTest {
    interface Mutation { void apply(JSONObject page) throws Exception; }
    static JSONObject page;
    static void rejects(String name, Mutation mutation) throws Exception {
        JSONObject changed = new JSONObject(page.toString());
        mutation.apply(changed);
        try { OShareSendingAssets.validatePage(changed); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(name + " accepted");
    }
    public static void main(String[] args) throws Exception {
        try (ZipFile zip = new ZipFile(args[0])) {
            page = new JSONObject(new String(zip.getInputStream(zip.getEntry("pages/index.json")).readAllBytes(), StandardCharsets.UTF_8));
        }
        OShareSendingAssets.validatePage(page);
        rejects("unscoped cancel", p -> p.getJSONObject("actions").getJSONObject("btnOnClick").getJSONObject("params").remove("instanceId"));
        rejects("native target", p -> p.getJSONObject("actions").getJSONObject("btnOnClick").put("uri", "other.provider"));
        rejects("unknown action", p -> p.getJSONObject("actions").getJSONObject("btnOnClick").getJSONObject("params").put("event", "cancelAll"));
        rejects("uncontrolled button", p -> p.getJSONArray("template").put(new JSONObject()
                .put("events", new JSONObject().put("click", "btnOnClick")).put("shown", true)));
        rejects("unknown event", p -> p.getJSONArray("template").put(new JSONObject()
                .put("events", new JSONObject().put("longClick", "btnOnClick")).put("shown", "{{isShowBtn}}")));
        rejects("empty layout", p -> p.put("template", new org.json.JSONArray()));
        System.out.println("OShareSendingAssets: original signed-template contract and 6 unsafe mutations checked");
    }
}
