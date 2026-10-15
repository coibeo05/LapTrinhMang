package quiz.db;

import quiz.model.ChatMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/** Lưu chat của một trận vào bảng ChatMessage (bảng đã được DatabaseManager tạo sẵn). */
public class ChatMessageDAO {
    public void insertAll(int matchId, List<ChatMessage> list) {
        if (list.isEmpty()) return;
        String sql = "INSERT INTO ChatMessage(matchId, accountId, noiDung, thoiGianGui) VALUES (?,?,?,?)";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (ChatMessage m : list) {
                ps.setInt(1, matchId);
                ps.setInt(2, m.accountId());
                ps.setString(3, m.noiDung());
                ps.setTimestamp(4, Timestamp.valueOf(m.thoiGianGui()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}