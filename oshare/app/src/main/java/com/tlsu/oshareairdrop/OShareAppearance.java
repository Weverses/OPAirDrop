package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/** Uses the host's existing vector artwork and native extended-device renderer. */
public final class OShareAppearance {
    private static final ConcurrentHashMap<String, Bitmap> ICONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AirDropPresentation.Kind> KINDS = new ConcurrentHashMap<>();
    private OShareAppearance() {}

    public interface DeviceAccess {
        String key(Object device) throws Exception;
        void setType(Object device, int value) throws Exception;
        void setIcon(Object device, Bitmap bitmap) throws Exception;
    }

    public static void apply(Context context, Object device, JSONObject target, DeviceAccess access) throws Exception {
        String peer = "airdrop:" + target.getLong("id");
        AirDropPresentation.Kind candidate = AirDropPresentation.kind(target.optInt("type"),
                target.optString("model"), target.optString("name"));
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                "oshare_airdrop_device_types", Context.MODE_PRIVATE);
        AirDropPresentation.Kind saved = null;
        String value = preferences.getString(peer, null);
        if (value != null) try { saved = AirDropPresentation.Kind.valueOf(value); }
        catch (IllegalArgumentException ignored) {}
        final AirDropPresentation.Kind remembered = saved;
        AirDropPresentation.Kind kind = KINDS.compute(peer,
                (key, previous) -> AirDropPresentation.preferKnown(previous == null ? remembered : previous, candidate));
        if (kind != AirDropPresentation.Kind.UNKNOWN && kind != saved)
            preferences.edit().putString(peer, kind.name()).apply();
        applyKind(context, device, kind, access);
    }

    public static void restore(Context context, Object device, DeviceAccess access) throws Exception {
        String peer = access.key(device);
        AirDropPresentation.Kind kind = KINDS.get(peer);
        if (kind != null) applyKind(context, device, kind, access);
    }

    private static void applyKind(Context context, Object device, AirDropPresentation.Kind kind, DeviceAccess access) throws Exception {
        access.setType(device, kind.oshareType);
        String key = kind.drawable + ":" + context.getResources().getConfiguration().hashCode();
        Bitmap bitmap = ICONS.get(key);
        if (bitmap == null || bitmap.isRecycled()) {
            int id = context.getResources().getIdentifier(kind.drawable, "drawable", context.getPackageName());
            if (id == 0) throw new IllegalStateException("OShare drawable missing: " + kind.drawable);
            Drawable drawable = context.getDrawable(id);
            if (drawable == null) throw new IllegalStateException("OShare drawable unavailable: " + kind.drawable);
            int size = Math.max(1, Math.round(64 * context.getResources().getDisplayMetrics().density));
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new Canvas(bitmap));
            ICONS.put(key, bitmap);
        }
        access.setIcon(device, bitmap);
    }

    public static void renameSection(Activity activity) throws Exception {
        int id = activity.getResources().getIdentifier("text_other", "id", activity.getPackageName());
        if (id == 0) return;
        rename(activity.getWindow().getDecorView(), id);
        // OShare builds the actual sharing panel in a COUIBottomSheetDialog.
        for (Class<?> type = activity.getClass(); type != Activity.class && type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Dialog.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Dialog dialog = (Dialog) field.get(activity);
                if (dialog != null && dialog.getWindow() != null) rename(dialog.getWindow().getDecorView(), id);
            }
        }
    }

    private static void rename(View root, int id) {
        View view = root.findViewById(id);
        if (view instanceof TextView && !"AirDrop".contentEquals(((TextView) view).getText())) {
            ((TextView) view).setText("AirDrop");
        }
    }
}
