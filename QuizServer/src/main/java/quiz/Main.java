package quiz;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/** Chạy: java -jar QuizServer.jar [cổng]   (mặc định 9000) */
public class Main {
    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : 9000;
        Class.forName("quiz.db.DatabaseManager");   // lỗi kết nối DB hiện ngay khi bật server
        if (!quiz.db.DatabaseManager.ping())
            System.err.println("!!! CẢNH BÁO: chưa kết nối được MySQL, người chơi sẽ KHÔNG đăng nhập/đăng ký được. Bật MySQL (XAMPP) rồi kiểm tra db.properties.");
        Game g = new Game();
        quiz.util.AdminConfig.announce();
        PingService.start();
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