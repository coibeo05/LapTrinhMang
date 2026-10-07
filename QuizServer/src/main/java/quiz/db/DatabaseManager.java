package quiz.db;

import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Quản lý kết nối cơ sở dữ liệu MySQL và tự động tạo bảng theo thiết kế Mục 3.4.
 */
public class DatabaseManager {
    private static String dbHost = "localhost";
    private static String dbPort = "3306";
    private static String dbName = "quiz_game";
    private static String dbUser = "root";
    private static String dbPass = "";

    static {
        loadConfig();
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            initDatabaseAndTables();
        } catch (ClassNotFoundException e) {
            System.err.println("Lỗi nạp Driver MySQL (com.mysql.cj.jdbc.Driver): " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("------------------------------------------------------------------");
            System.err.println("LỖI KẾT NỐI MYSQL: " + e.getMessage());
            System.err.println("Vui lòng kiểm tra:");
            System.err.println(" 1. Dịch vụ MySQL đã được bật chưa? (XAMPP / MySQL Service)");
            System.err.println(" 2. Kiểm tra tài khoản và mật khẩu trong file 'db.properties' (user=" + dbUser + ", port=" + dbPort + ")");
            System.err.println("------------------------------------------------------------------");
        }
    }

    private static void loadConfig() {
        Properties props = new Properties();
        try (InputStream is = new FileInputStream("db.properties")) {
            props.load(is);
        } catch (Exception ignored) {
            // Nếu không tìm thấy file ngoài, thử tìm trong classpath
            try (InputStream is = DatabaseManager.class.getClassLoader().getResourceAsStream("db.properties")) {
                if (is != null) props.load(is);
            } catch (Exception ignored2) { }
        }

        dbHost = System.getProperty("quiz.db.host", props.getProperty("db.host", dbHost));
        dbPort = System.getProperty("quiz.db.port", props.getProperty("db.port", dbPort));
        dbName = System.getProperty("quiz.db.name", props.getProperty("db.name", dbName));
        dbUser = System.getProperty("quiz.db.user", props.getProperty("db.user", dbUser));
        dbPass = System.getProperty("quiz.db.password", props.getProperty("db.password", dbPass));
    }

    private static String getBaseUrl() {
        return "jdbc:mysql://" + dbHost + ":" + dbPort + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8";
    }

    private static String getDbUrl() {
        return "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8";
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getDbUrl(), dbUser, dbPass);
    }

    /** Tự động tạo Database nếu chưa có và khởi tạo 5 bảng theo Mục 3.4 */
    private static void initDatabaseAndTables() throws SQLException {
        // 1. Tạo Database nếu chưa tồn tại
        try (Connection conn = DriverManager.getConnection(getBaseUrl(), dbUser, dbPass);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;");
        }

        // 2. Tạo 5 bảng theo lược đồ CSDL mục 3.4 trong tài liệu
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            // Bảng 1: Account
            st.execute("""
                CREATE TABLE IF NOT EXISTS `Account` (
                    `accountId` INT AUTO_INCREMENT PRIMARY KEY,
                    `username` VARCHAR(50) NOT NULL UNIQUE,
                    `passwordHash` VARCHAR(255) NOT NULL,
                    `tongDiem` INT DEFAULT 0,
                    `tongTranThang` INT DEFAULT 0,
                    `ngayTao` DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """);

            // Bảng 2: Question
            st.execute("""
                CREATE TABLE IF NOT EXISTS `Question` (
                    `questionId` INT AUTO_INCREMENT PRIMARY KEY,
                    `chuDe` VARCHAR(100) NOT NULL,
                    `noiDung` TEXT NOT NULL,
                    `loaiCauHoi` VARCHAR(20) NOT NULL,
                    `duLieuDapAn` JSON NOT NULL,
                    `doKho` VARCHAR(20) DEFAULT 'DE',
                    `nguoiTaoId` INT NULL,
                    FOREIGN KEY (`nguoiTaoId`) REFERENCES `Account` (`accountId`) ON DELETE SET NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """);

            // Bảng 3: MatchResult
            st.execute("""
                CREATE TABLE IF NOT EXISTS `MatchResult` (
                    `matchId` INT AUTO_INCREMENT PRIMARY KEY,
                    `chuDe` VARCHAR(100),
                    `thoiGianKetThuc` DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """);

            // Bảng 4: MatchResultDetail
            st.execute("""
                CREATE TABLE IF NOT EXISTS `MatchResultDetail` (
                    `matchId` INT NOT NULL,
                    `accountId` INT NOT NULL,
                    `diemSo` INT DEFAULT 0,
                    `thuHang` INT NOT NULL,
                    PRIMARY KEY (`matchId`, `accountId`),
                    FOREIGN KEY (`matchId`) REFERENCES `MatchResult` (`matchId`) ON DELETE CASCADE,
                    FOREIGN KEY (`accountId`) REFERENCES `Account` (`accountId`) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """);

            // Bảng 5: ChatMessage
            st.execute("""
                CREATE TABLE IF NOT EXISTS `ChatMessage` (
                    `chatMessageId` INT AUTO_INCREMENT PRIMARY KEY,
                    `matchId` INT NOT NULL,
                    `accountId` INT NOT NULL,
                    `noiDung` TEXT NOT NULL,
                    `thoiGianGui` DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (`matchId`) REFERENCES `MatchResult` (`matchId`) ON DELETE CASCADE,
                    FOREIGN KEY (`accountId`) REFERENCES `Account` (`accountId`) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """);

            System.out.println("CSDL MySQL [" + dbName + "] đã sẵn sàng tại " + dbHost + ":" + dbPort);
        }
    }
}
