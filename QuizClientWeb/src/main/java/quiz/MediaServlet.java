package quiz;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** GET /media/<tên> - chỉ phục vụ file có tên hợp lệ trong kho media. */
@WebServlet("/media/*")
public class MediaServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String n = req.getPathInfo() == null ? "" : req.getPathInfo().substring(1);
        Path f = MediaStore.DIR.resolve(MediaStore.NAME.matcher(n).matches() ? n : "_");
        if (!Files.isRegularFile(f)) { res.sendError(404); return; }
        res.setContentType(switch (MediaStore.ext(n)) {
            case "jpg" -> "image/jpeg"; case "png" -> "image/png"; case "webp" -> "image/webp";
            case "mp3" -> "audio/mpeg"; case "ogg" -> "audio/ogg"; case "wav" -> "audio/wav"; default -> "audio/mp4";
        });
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Cache-Control", "public, max-age=86400");
        res.setContentLengthLong(Files.size(f));
        Files.copy(f, res.getOutputStream());
    }
}
