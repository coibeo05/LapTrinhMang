package quiz.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bảng xếp hạng toàn hệ thống, tính từ lịch sử các trận đã lưu (MatchResultDetail):
 *  - Theo số trận chơi: số trận giảm dần, rồi số trận thắng.
 *  - Theo chuỗi thắng liên tiếp: chuỗi dài nhất giảm dần, rồi chuỗi hiện tại, rồi số trận.
 * "Thắng" = hạng 1 và có điểm > 0 (cùng quy ước với cập nhật tổng trận thắng của tài khoản).
 */
public class RankingDAO {

    /** Thống kê của một người chơi. */
    public record Stat(int accountId, String username, int played, int wins, int curStreak, int bestStreak) { }

    /** Một dòng trên bảng xếp hạng. */
    public record Row(int hang, Stat stat) { }

    /** Đọc toàn bộ lịch sử và tổng hợp cho mọi tài khoản đã chơi ít nhất một trận. */
    public List<Stat> loadAll() {
        Map<Integer, String> names = new HashMap<>();
        List<int[]> details = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT accountId, username FROM Account");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.put(rs.getInt(1), rs.getString(2));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT accountId, thuHang, diemSo FROM MatchResultDetail ORDER BY accountId, matchId");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) details.add(new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)});
            }
        } catch (SQLException e) {
            System.err.println("Lỗi lấy bảng xếp hạng: " + e.getMessage());
            return new ArrayList<>();
        }
        return aggregate(details, names);
    }

    /**
     * @param details các dòng {accountId, thuHang, diemSo}, đã sắp theo accountId rồi theo thứ tự trận (cũ -> mới)
     */
    public static List<Stat> aggregate(List<int[]> details, Map<Integer, String> names) {
        Map<Integer, int[]> acc = new LinkedHashMap<>();   // accountId -> {played, wins, cur, best}
        for (int[] d : details) {
            int[] a = acc.computeIfAbsent(d[0], k -> new int[4]);
            a[0]++;
            if (d[1] == 1 && d[2] > 0) {
                a[1]++;
                a[2]++;
                a[3] = Math.max(a[3], a[2]);
            } else {
                a[2] = 0;
            }
        }
        List<Stat> out = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : acc.entrySet()) {
            int[] a = e.getValue();
            out.add(new Stat(e.getKey(), names.getOrDefault(e.getKey(), "?"), a[0], a[1], a[2], a[3]));
        }
        return out;
    }

    public static List<Row> sortByPlayed(List<Stat> all, int limit) {
        return rank(all, Comparator.comparingInt(Stat::played).reversed()
                .thenComparing(Comparator.comparingInt(Stat::wins).reversed())
                .thenComparingInt(Stat::accountId), false, limit);
    }

    public static List<Row> sortByStreak(List<Stat> all, int limit) {
        return rank(all, Comparator.comparingInt(Stat::bestStreak).reversed()
                .thenComparing(Comparator.comparingInt(Stat::curStreak).reversed())
                .thenComparing(Comparator.comparingInt(Stat::played).reversed())
                .thenComparingInt(Stat::accountId), true, limit);
    }

    private static List<Row> rank(List<Stat> all, Comparator<Stat> cmp, boolean needStreak, int limit) {
        List<Stat> s = new ArrayList<>();
        for (Stat x : all) if (!needStreak || x.bestStreak() > 0) s.add(x);   // bảng chuỗi thắng: chỉ ai từng thắng
        s.sort(cmp);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) rows.add(new Row(i + 1, s.get(i)));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /** Thứ hạng của một người trong danh sách đầy đủ (1 = cao nhất), 0 nếu không có trong bảng. */
    public static int rankOf(List<Row> fullRows, int accountId) {
        for (Row r : fullRows) if (r.stat().accountId() == accountId) return r.hang();
        return 0;
    }
}