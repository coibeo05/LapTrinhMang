package quiz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/** Kho file ảnh / âm thanh do người chơi tải lên (ngoài thư mục WAR để redeploy không mất). */
final class MediaStore {
    static final Path DIR = Path.of(System.getProperty("user.home"), "quizmedia");
    static final Pattern NAME = Pattern.compile("[0-9a-f\\-]{36}\\.(jpg|png|webp|mp3|ogg|wav|m4a)");
    static final Set<String> IMG = Set.of("jpg", "png", "webp"), AUD = Set.of("mp3", "ogg", "wav", "m4a");

    static String ext(String n) {
        int i = n.lastIndexOf('.');
        String e = i < 0 ? "" : n.substring(i + 1).toLowerCase();
        return e.equals("jpeg") ? "jpg" : e;
    }

    /** url dạng "media/<tên>": đúng định dạng, đúng loại và file tồn tại. */
    static boolean valid(String url, Set<String> exts) {
        if (url == null || !url.startsWith("media/")) return false;
        String n = url.substring(6);
        return NAME.matcher(n).matches() && exts.contains(ext(n)) && Files.isRegularFile(DIR.resolve(n));
    }

    /** Kiểm tra chữ ký (magic bytes) để không tin phần đuôi file. */
    static boolean magic(String ext, byte[] b) {
        if (b.length < 12) return false;
        boolean riff = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F';
        switch (ext) {
            case "png": return (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "jpg": return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
            case "webp": return riff && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "wav": return riff && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
            case "ogg": return b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S';
            case "m4a": return b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
            case "mp3": return (b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0);
            default: return false;
        }
    }
}
