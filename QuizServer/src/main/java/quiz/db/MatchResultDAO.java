package quiz.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object cho MatchResult và MatchResultDetail (Mục 3.4, Mục 4.1 Hình 3 tài liệu đặc tả).
 */
public class MatchResultDAO {

    public record HistoryItem(int matchId, String chuDe, String thoiGian, int diemSo, int thuHang) {}

    public int createMatch(String chuDe) {
        String sql = "INSERT INTO MatchResult (chuDe, thoiGianKetThuc) VALUES (?, NOW())";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, chuDe != null ? chuDe : "Trận đấu Quiz");
            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi lưu MatchResult: " + e.getMessage());
        }
        return -1;
    }

    public void addDetail(int matchId, int accountId, int diemSo, int thuHang) {
        String sql = "INSERT INTO MatchResultDetail (matchId, accountId, diemSo, thuHang) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, matchId);
            ps.setInt(2, accountId);
            ps.setInt(3, diemSo);
            ps.setInt(4, thuHang);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Lỗi lưu MatchResultDetail: " + e.getMessage());
        }
    }

    /** Lấy lịch sử thi đấu của một người chơi (Mục 4.1 Hình 3) */
    public List<HistoryItem> getPersonalHistory(int accountId, int limit) {
        String sql = """
            SELECT m.matchId, m.chuDe, m.thoiGianKetThuc, d.diemSo, d.thuHang
            FROM MatchResultDetail d
            JOIN MatchResult m ON d.matchId = m.matchId
            WHERE d.accountId = ?
            ORDER BY m.thoiGianKetThuc DESC
            LIMIT ?
        """;
        List<HistoryItem> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new HistoryItem(
                        rs.getInt("matchId"),
                        rs.getString("chuDe"),
                        rs.getString("thoiGianKetThuc"),
                        rs.getInt("diemSo"),
                        rs.getInt("thuHang")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi lấy lịch sử thi đấu: " + e.getMessage());
        }
        return list;
    }
}

