package com.tlsu.oshareairdrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Messenger clientMessenger = new Messenger(new Handler(Looper.getMainLooper(),
            this::onBridgeMessage));

    private TextView logView;
    private Messenger serverMessenger;
    private long lastTargetId;
    private boolean auto;

    private boolean onBridgeMessage(Message message) {
        if (message.what == BridgeMessenger.MSG_HELLO) {
            Object obj = message.replyTo;
            if (obj instanceof Messenger) {
                serverMessenger = (Messenger) obj;
                append("bridge ready");
                sendCommand(command("ping"));
                if (auto) {
                    mainHandler.postDelayed(() -> sendCommand(command("discover_start")), 300);
                }
            }
            return true;
        }
        if (message.what == BridgeMessenger.MSG_EVENT) {
            String json = message.getData().getString(BridgeMessenger.EXTRA_JSON, "");
            if (!json.isEmpty()) {
                try {
                    JSONObject event = new JSONObject(json);
                    if ("target".equals(event.optString("ev"))) {
                        lastTargetId = event.optLong("id");
                    }
                    append(event.optString("ev") + " " + json);
                } catch (Throwable t) {
                    append(json);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auto = getIntent() != null && getIntent().getBooleanExtra("auto", false);
        setContentView(buildUi());
        if (auto) mainHandler.postDelayed(this::bootMosey, 2500);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("OShare AirDrop · 0.10");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(12), 0, 0);
        row1.addView(button("Boot Mosey", v -> bootMosey()));
        row1.addView(button("Discover", v -> sendCommand(command("discover_start"))));
        row1.addView(button("Stop", v -> sendCommand(command("discover_stop"))));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        row2.addView(button("Advertise", v -> {
            JSONObject json = command("advertise_start");
            try {
                json.put("name", "OPAirShare");
            } catch (Throwable ignored) {
            }
            sendCommand(json);
        }));
        row2.addView(button("Send test", v -> {
            if (lastTargetId == 0) {
                append("no target discovered yet");
            } else {
                JSONObject json = command("send_test");
                try {
                    json.put("id", lastTargetId);
                } catch (Throwable ignored) {
                }
                sendCommand(json);
            }
        }));
        root.addView(row2);

        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(0, dp(12), 0, 0);
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextColor(Color.DKGRAY);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        scroll.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);
        return root;
    }

    private JSONObject command(String cmd) {
        try {
            return new JSONObject().put("cmd", cmd);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bootMosey() {
        try {
            Intent intent = new Intent();
            intent.setClassName(Entry.HOST, Entry.HOST + ".MoseyPermissionActivity");
            intent.putExtra(Entry.BOOT_EXTRA, true);
            intent.putExtra(BridgeMessenger.EXTRA_CLIENT, clientMessenger);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            append("started MoseyPermissionActivity");
        } catch (Throwable t) {
            append("boot Mosey failed: " + t);
        }
    }

    private void sendCommand(JSONObject json) {
        if (serverMessenger == null) {
            append("bridge not ready; tap Boot Mosey first");
            return;
        }
        try {
            Bundle bundle = new Bundle();
            bundle.putString(BridgeMessenger.EXTRA_JSON, json.toString());
            Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND);
            message.setData(bundle);
            serverMessenger.send(message);
            append("> " + json);
        } catch (Throwable t) {
            append("send failed: " + t);
        }
    }

    private void append(String message) {
        mainHandler.post(() -> {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            logView.append("[" + time + "] " + message + "\n");
            int scroll = logView.getLayout() == null ? 0
                    : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            if (scroll > 0) logView.scrollTo(0, scroll);
        });
    }
}
