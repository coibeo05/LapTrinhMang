package quiz;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/** POST /upload?kind=anh|amThanh (multipart, trường "file") -> {"url":"media/<tên>"}. Ảnh <= 2 MB, âm thanh <= 5 MB. */
@WebServlet("/upload")
@MultipartConfig(maxFileSize = 5 * 1024 * 1024, maxRequestSize = 6 * 1024 * 1024)
public class UploadServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json;charset=UTF-8");
        boolean img = "anh".equals(req.getParameter("kind"));
        try {
            Part p = req.getPart("file");
            String ext = p == null ? "" : MediaStore.ext(p.getSubmittedFileName() == null ? "" : p.getSubmittedFileName());
            if (!(img ? MediaStore.IMG : MediaStore.AUD).contains(ext)) { fail(res, "Định dạng không được hỗ trợ"); return; }
            if (img && p.getSize() > 2 * 1024 * 1024) { fail(res, "Ảnh tối đa 2 MB"); return; }
            byte[] data = p.getInputStream().readAllBytes();
            if (!MediaStore.magic(ext, data)) { fail(res, "Nội dung file không đúng định dạng"); return; }
            Files.createDirectories(MediaStore.DIR);
            String name = UUID.randomUUID() + "." + ext;
            Files.write(MediaStore.DIR.resolve(name), data);
            res.getWriter().write("{\"url\":\"media/" + name + "\"}");
        } catch (IllegalStateException | ServletException e) {
            fail(res, "File quá lớn hoặc không hợp lệ");
        }
    }

    private static void fail(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(400);
        res.getWriter().write("{\"loi\":\"" + msg + "\"}");
    }
}
