package quiz;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * CẦU NỐI WebSocket <-> TCP. Mỗi tab trình duyệt = 1 kết nối TCP riêng tới QuizServer.
 * Không xử lý nghiệp vụ, chỉ chuyển nguyên từng dòng JSON qua lại.
 * Địa chỉ Server: -Dquiz.server.host / -Dquiz.server.port hoặc biến môi trường QUIZ_SERVER_HOST / QUIZ_SERVER_PORT.
 */
@ServerEndpoint("/ws")
public class Bridge {
    private static String cfg(String key, String def) {
        String v = System.getProperty(key, System.getenv(key.toUpperCase().replace('.', '_')));
        return v == null ? def : v;
    }

    @OnOpen
    public void onOpen(Session ws) throws IOException {
        try {
            Socket sk = new Socket();
            sk.connect(new InetSocketAddress(cfg("quiz.server.host", "localhost"),
                    Integer.parseInt(cfg("quiz.server.port", "9000"))), 3000);
            ws.getUserProperties().put("sock", sk);
            ws.getUserProperties().put("out", new BufferedWriter(new OutputStreamWriter(sk.getOutputStream(), StandardCharsets.UTF_8)));
            Thread t = new Thread(() -> pump(ws, sk), "bridge-" + ws.getId());
            t.setDaemon(true);
            t.start();
        } catch (IOException | RuntimeException e) {
            ws.getBasicRemote().sendText("{\"type\":\"ERROR\",\"payload\":{\"thongBao\":\"Không kết nối được Server\"}}");
            ws.close();
        }
    }

    /** Server -> trình duyệt. */
    private static void pump(Session ws, Socket sk) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(sk.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null)
                synchronized (ws) { if (ws.isOpen()) ws.getBasicRemote().sendText(line); }
        } catch (IOException e) { /* TCP đóng */ }
        try { if (ws.isOpen()) ws.close(); } catch (IOException e) { }
    }

    /** Trình duyệt -> Server. */
    @OnMessage(maxMessageSize = 1_000_000)
    public void onMessage(String msg, Session ws) throws IOException {
        BufferedWriter out = (BufferedWriter) ws.getUserProperties().get("out");
        if (out == null) return;
        synchronized (out) { out.write(msg.replace('\n', ' ').replace('\r', ' ')); out.write('\n'); out.flush(); }
    }

    @OnClose
    public void onClose(Session ws) {
        Object sk = ws.getUserProperties().get("sock");
        if (sk instanceof Socket s) try { s.close(); } catch (IOException e) { }
    }

    @OnError
    public void onError(Session ws, Throwable t) { onClose(ws); }
}
