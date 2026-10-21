package com.tlsu.oshareairdrop;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BridgeServer {
    public static final String SOCKET_NAME = "oshare_airdrop_bridge";
    private static final String TAG = "OShareAirDrop";

    private static volatile boolean started;
    private static volatile LocalServerSocket serverSocket;
    private static final List<Client> CLIENTS = new CopyOnWriteArrayList<>();

    private BridgeServer() {}

    public static synchronized void startIfNeeded() {
        if (started) return;
        started = true;
        ProviderApi.setSink(BridgeServer::broadcast);
        Thread thread = new Thread(BridgeServer::acceptLoop, "OShareAirDrop-Bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private static void acceptLoop() {
        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
            Log.i(TAG, "bridge listening on @" + SOCKET_NAME);
            while (true) {
                LocalSocket socket = serverSocket.accept();
                Client client = new Client(socket);
                CLIENTS.add(client);
                client.start();
                Log.i(TAG, "client connected: " + socket);
            }
        } catch (Throwable t) {
            Log.e(TAG, "bridge server stopped", t);
            started = false;
        }
    }

    private static void broadcast(JSONObject event) {
        String line = event.toString();
        for (Client client : CLIENTS) {
            client.send(line);
        }
    }

    private static void dispatch(Client client, JSONObject command) {
        String cmd = command.optString("cmd", "");
        try {
            switch (cmd) {
                case "ping":
                    client.send(new JSONObject().put("ev", "pong").put("ts", System.currentTimeMillis()).toString());
                    break;
                case "discover_start":
                    ProviderApi.startDiscovery();
                    break;
                case "discover_stop":
                    ProviderApi.stopDiscovery();
                    break;
                case "advertise_start":
                    ProviderApi.startAdvertising(command.optString("name", "OnePlus"));
                    break;
                case "advertise_stop":
                    ProviderApi.stopAdvertising();
                    break;
                case "send":
                    ProviderApi.sendPath(command.getString("path"), command.getLong("id"));
                    break;
                case "send_test":
                    ProviderApi.sendTestFile(command.getLong("id"));
                    break;
                case "accept":
                    ProviderApi.accept(command.getLong("id"));
                    break;
                case "deny":
                    ProviderApi.deny(command.getLong("id"));
                    break;
                case "cancel":
                    ProviderApi.cancel(command.getLong("id"));
                    break;
                case "set_download_dir":
                    ProviderApi.setDownloadDirectory(command.getString("path"));
                    break;
                default:
                    client.send(new JSONObject().put("ev", "error").put("message", "unknown cmd: " + cmd).toString());
            }
        } catch (Throwable t) {
            Log.e(TAG, "command failed: " + cmd, t);
            try {
                client.send(new JSONObject().put("ev", "error").put("cmd", cmd)
                        .put("message", String.valueOf(t)).toString());
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class Client {
        private final LocalSocket socket;
        private final BufferedWriter writer;
        private final Thread thread;

        Client(LocalSocket socket) throws Exception {
            this.socket = socket;
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            this.thread = new Thread(this::readLoop, "OShareAirDrop-Client");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
            send("{\"ev\":\"hello\",\"socket\":\"" + SOCKET_NAME + "\"}");
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "client> " + line);
                    try {
                        dispatch(this, new JSONObject(line));
                    } catch (Throwable t) {
                        send(new JSONObject().put("ev", "error")
                                .put("message", "bad json: " + t).toString());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "client read ended: " + t);
            } finally {
                CLIENTS.remove(this);
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }

        synchronized void send(String line) {
            try {
                writer.write(line);
                writer.write("\n");
                writer.flush();
            } catch (Throwable t) {
                CLIENTS.remove(this);
            }
        }
    }
}
