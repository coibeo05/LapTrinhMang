package quiz.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Nhật ký hoạt động gần đây (trong RAM) cho trang quản trị. */
public final class ActivityLog {
    private static final int MAX = 60;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String[]> LOG = new ArrayDeque<>();

    private ActivityLog() { }

    public static synchronized void add(String text) {
        LOG.addFirst(new String[]{LocalTime.now().format(FMT), text});
        while (LOG.size() > MAX) LOG.removeLast();
    }

    /** Mới nhất đứng đầu. Mỗi phần tử: {giờ, nội dung}. */
    public static synchronized List<String[]> recent(int n) {
        List<String[]> r = new ArrayList<>();
        for (String[] e : LOG) { if (r.size() >= n) break; r.add(e); }
        return r;
    }
}