package quiz;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/** Chạy: java -jar QuizServer.jar [cổng]   (mặc định 9000) */
public class Main {
    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : 9000;
        Game g = new Game();
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("QuizServer đang lắng nghe cổng " + port);
            while (true) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> { try { new Conn(s).run(g); } catch (IOException e) { } }, "conn");
                t.setDaemon(true);
                t.start();
            }
        }
    }
}
