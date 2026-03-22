package uz.sozboyligi.fps;

import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class WsServer extends Thread {

    private static final int PORT = 8765;
    private static final String TAG = "WsServer";
    private ServerSocket serverSocket;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private volatile boolean running = true;
    private boolean gameStarted = false;

    // Spawn points
    private static final double[][] SPAWNS = {
        {2.5,2.5},{21.5,2.5},{2.5,18.5},{21.5,18.5},
        {12,2.5},{2.5,10},{21.5,10},{12,18.5},
        {5.5,5.5},{18.5,14.5},{5.5,14.5},{18.5,5.5}
    };
    private int spawnIdx = 0;

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT, 10, InetAddress.getByName("0.0.0.0"));
            serverSocket.setSoTimeout(0);
            Log.d(TAG, "WsServer started on port " + PORT);

            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    s.setTcpNoDelay(true);
                    new Thread(() -> handleClient(s)).start();
                } catch (Exception e) {
                    if (running) Log.e(TAG, "Accept error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        Client client = null;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // WebSocket handshake
            if (!doHandshake(in, out)) { socket.close(); return; }

            // Create client
            String id = "p" + idCounter.incrementAndGet();
            client = new Client(id, socket, in, out);
            clients.add(client);
            final Client fc = client;

            // Send welcome
            JSONObject welcome = new JSONObject();
            welcome.put("t", "welcome");
            welcome.put("id", id);
            JSONArray plist = new JSONArray();
            for (Client c : clients) {
                if (c.id.equals(id)) continue;
                JSONObject p = new JSONObject();
                p.put("id", c.id);
                p.put("name", c.name);
                p.put("color", c.color);
                p.put("x", c.x); p.put("y", c.y);
                p.put("hp", c.hp);
                plist.put(p);
            }
            welcome.put("players", plist);
            welcome.put("serverIp", getLocalIp());
            sendTo(client, welcome.toString());

            // Read messages
            while (running && !socket.isClosed()) {
                String msg = readFrame(in);
                if (msg == null) break;
                processMessage(fc, msg);
            }

        } catch (Exception e) {
            Log.d(TAG, "Client disconnected: " + (client != null ? client.id : "?"));
        } finally {
            if (client != null) {
                clients.remove(client);
                try {
                    JSONObject leave = new JSONObject();
                    leave.put("t", "leave");
                    leave.put("id", client.id);
                    broadcast(leave.toString(), null);
                } catch (Exception ex) {}
                try { socket.close(); } catch (Exception ex) {}
            }
        }
    }

    private void processMessage(Client from, String raw) throws Exception {
        JSONObject msg = new JSONObject(raw);
        String t = msg.getString("t");

        switch (t) {
            case "hello":
                from.name = msg.optString("name", "Player");
                from.color = msg.optString("color", "#ff4444");
                // Assign spawn
                double[] sp = SPAWNS[spawnIdx % SPAWNS.length]; spawnIdx++;
                from.x = sp[0]; from.y = sp[1];
                // Broadcast join
                JSONObject join = new JSONObject();
                join.put("t", "join");
                join.put("id", from.id);
                join.put("name", from.name);
                join.put("color", from.color);
                join.put("x", from.x); join.put("y", from.y);
                broadcast(join.toString(), from.id);
                break;

            case "pos":
                from.x = msg.optDouble("x", from.x);
                from.y = msg.optDouble("y", from.y);
                from.angle = msg.optDouble("a", from.angle);
                from.dead = msg.optBoolean("dead", false);
                // Relay to all others
                JSONObject pos = new JSONObject();
                pos.put("t", "pos");
                pos.put("id", from.id);
                pos.put("x", from.x); pos.put("y", from.y);
                pos.put("a", from.angle);
                pos.put("dead", from.dead);
                broadcast(pos.toString(), from.id);
                break;

            case "shoot":
                // Relay to all others
                msg.put("id", from.id);
                broadcast(msg.toString(), from.id);
                break;

            case "hit":
                // Relay to victim and others
                msg.put("shooter", from.id);
                // Update victim hp on server
                String victimId = msg.optString("victim");
                int dmg = msg.optInt("damage", 25);
                for (Client c : clients) {
                    if (c.id.equals(victimId)) {
                        c.hp = Math.max(0, c.hp - dmg);
                        break;
                    }
                }
                broadcast(msg.toString(), null);
                break;

            case "dead":
                from.dead = true;
                msg.put("id", from.id);
                broadcast(msg.toString(), null);
                break;

            case "respawn_req":
                from.dead = false;
                from.hp = 100;
                double[] rsp = SPAWNS[spawnIdx % SPAWNS.length]; spawnIdx++;
                from.x = rsp[0]; from.y = rsp[1];
                JSONObject respawn = new JSONObject();
                respawn.put("t", "respawn");
                respawn.put("id", from.id);
                respawn.put("x", from.x);
                respawn.put("y", from.y);
                broadcast(respawn.toString(), null);
                break;

            case "start_req":
                // Only first connected player can start
                if (!gameStarted && clients.size() > 0 && clients.get(0).id.equals(from.id)) {
                    gameStarted = true;
                    JSONObject start = new JSONObject();
                    start.put("t", "start");
                    JSONObject spawns = new JSONObject();
                    int si = 0;
                    for (Client c : clients) {
                        double[] csp = SPAWNS[si % SPAWNS.length]; si++;
                        c.x = csp[0]; c.y = csp[1]; c.dead = false; c.hp = 100;
                        JSONObject cspo = new JSONObject();
                        cspo.put("x", c.x); cspo.put("y", c.y);
                        spawns.put(c.id, cspo);
                    }
                    start.put("spawns", spawns);
                    broadcast(start.toString(), null);
                }
                break;
        }
    }

    // ── WebSocket protocol ──────────────────────

    private boolean doHandshake(InputStream in, OutputStream out) throws Exception {
        StringBuilder sb = new StringBuilder();
        String wsKey = null;
        int b;
        char prev = 0;
        while ((b = in.read()) != -1) {
            char c = (char) b;
            sb.append(c);
            if (sb.length() > 4 && sb.substring(sb.length()-4).equals("\r\n\r\n")) break;
        }
        String req = sb.toString();
        for (String line : req.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                wsKey = line.split(":", 2)[1].trim();
            }
        }
        if (wsKey == null) return false;

        String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest((wsKey + magic).getBytes("UTF-8"));
        String accept = Base64.encodeToString(hash, Base64.NO_WRAP);

        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(resp.getBytes("UTF-8"));
        out.flush();
        return true;
    }

    private String readFrame(InputStream in) throws IOException {
        int b0 = in.read(), b1 = in.read();
        if (b0 == -1 || b1 == -1) return null;

        int opcode = b0 & 0x0F;
        if (opcode == 8) return null; // close
        if (opcode == 9) { // ping
            byte[] pong = {(byte)0x8A, 0x00};
            return readFrame(in); // skip ping
        }

        boolean masked = (b1 & 0x80) != 0;
        int payLen = b1 & 0x7F;

        if (payLen == 126) {
            payLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (payLen == 127) {
            for (int i = 0; i < 6; i++) in.read();
            payLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        }

        byte[] mask = new byte[4];
        if (masked) {
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        }

        byte[] payload = new byte[payLen];
        int total = 0;
        while (total < payLen) {
            int r = in.read(payload, total, payLen - total);
            if (r == -1) return null;
            total += r;
        }

        if (masked) {
            for (int i = 0; i < payLen; i++) payload[i] ^= mask[i % 4];
        }
        return new String(payload, "UTF-8");
    }

    private synchronized void sendTo(Client c, String msg) {
        try {
            byte[] payload = msg.getBytes("UTF-8");
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); // FIN + text
            if (payload.length < 126) {
                frame.write(payload.length);
            } else if (payload.length < 65536) {
                frame.write(126);
                frame.write((payload.length >> 8) & 0xFF);
                frame.write(payload.length & 0xFF);
            }
            frame.write(payload);
            c.out.write(frame.toByteArray());
            c.out.flush();
        } catch (Exception e) {
            Log.d(TAG, "Send error to " + c.id + ": " + e.getMessage());
        }
    }

    private void broadcast(String msg, String excludeId) {
        for (Client c : clients) {
            if (excludeId != null && c.id.equals(excludeId)) continue;
            sendTo(c, msg);
        }
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception e) {}
        return "192.168.43.1";
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
    }

    // ── Client ──────────────────────────────────
    static class Client {
        final String id;
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        String name = "Player";
        String color = "#ff4444";
        double x = 2.5, y = 2.5, angle = 0;
        int hp = 100;
        boolean dead = false;

        Client(String id, Socket socket, InputStream in, OutputStream out) {
            this.id = id; this.socket = socket; this.in = in; this.out = out;
        }
    }
}
