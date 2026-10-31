package com.discordmini.client.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Kiểm thử tích hợp thực tế End-to-End:
 * Browser HTTP/SSE -> UiBridgeServer -> Java TCP Socket -> ChatServer -> PostgreSQL.
 */
public class EndToEndWebAuthTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU END-TO-END WEB AUTH TEST ===");

        // Khởi động UiBridgeServer kết nối tới ChatServer (127.0.0.1:5000)
        UiBridgeServer bridge = new UiBridgeServer(18082, "127.0.0.1", 5000);
        bridge.start();

        int port = bridge.getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        try {
            long ts = System.currentTimeMillis();
            String user1 = "web_u1_" + ts;
            String pass1 = "MatKhau#123";
            String display1 = "Người Dùng Web 1";

            String user2 = "web_u2_" + ts;
            String pass2 = "MatKhau#456";
            String display2 = "Người Dùng Web 2";

            // 1. Đăng ký tài khoản 1
            System.out.println("1. Kiểm tra đăng ký tài khoản mới: " + user1);
            String regBody1 = JsonHelper.toJson(Map.of(
                    "username", user1,
                    "password", pass1,
                    "displayName", display1
            ));
            HttpResponse regRes1 = request("POST", baseUrl + "/api/register", regBody1, null);
            check(regRes1.statusCode == 200, "Đăng ký thành công phải trả về 200, nhận: " + regRes1.statusCode + " - " + regRes1.body);
            Map<String, Object> regMap1 = JsonHelper.parseObject(regRes1.body);
            check(Boolean.TRUE.equals(regMap1.get("success")), "success phải là true");
            System.out.println("   -> Đăng ký thành công: " + regMap1.get("message"));

            // 2. Đăng ký trùng tên tài khoản 1 -> Phải trả về lỗi 409
            System.out.println("2. Kiểm tra đăng ký trùng tên: " + user1);
            HttpResponse dupRes = request("POST", baseUrl + "/api/register", regBody1, null);
            check(dupRes.statusCode == 409 || dupRes.statusCode == 400, "Đăng ký trùng phải trả về 409 hoặc 400, nhận: " + dupRes.statusCode);
            Map<String, Object> dupMap = JsonHelper.parseObject(dupRes.body);
            check(Boolean.FALSE.equals(dupMap.get("success")), "success phải là false khi trùng tên");
            System.out.println("   -> Chặn trùng tài khoản thành công: " + dupMap.get("message"));

            // 3. Đăng nhập sai mật khẩu
            System.out.println("3. Kiểm tra đăng nhập sai mật khẩu");
            String wrongLoginBody = JsonHelper.toJson(Map.of(
                    "username", user1,
                    "password", "SaiMatKhau999"
            ));
            HttpResponse wrongRes = request("POST", baseUrl + "/api/login", wrongLoginBody, null);
            check(wrongRes.statusCode == 401, "Đăng nhập sai pass phải trả về 401, nhận: " + wrongRes.statusCode);
            Map<String, Object> wrongMap = JsonHelper.parseObject(wrongRes.body);
            check(Boolean.FALSE.equals(wrongMap.get("success")), "success phải là false khi sai mật khẩu");
            System.out.println("   -> Chặn sai mật khẩu thành công: " + wrongMap.get("message"));

            // 4. Đăng nhập đúng tài khoản 1 -> Nhận Session Cookie
            System.out.println("4. Kiểm tra đăng nhập đúng tài khoản 1");
            String correctLoginBody1 = JsonHelper.toJson(Map.of(
                    "username", user1,
                    "password", pass1
            ));
            HttpResponse loginRes1 = request("POST", baseUrl + "/api/login", correctLoginBody1, null);
            check(loginRes1.statusCode == 200, "Đăng nhập đúng phải trả về 200, nhận: " + loginRes1.statusCode);
            Map<String, Object> loginMap1 = JsonHelper.parseObject(loginRes1.body);
            check(Boolean.TRUE.equals(loginMap1.get("success")), "success phải là true");

            String cookie1 = extractSessionCookie(loginRes1.headers);
            check(cookie1 != null && !cookie1.isBlank(), "Phải nhận được Set-Cookie DISCORD_SESSION");
            System.out.println("   -> Đăng nhập thành công, nhận cookie phiên: " + cookie1.split(";")[0]);

            // 5. Kiểm tra GET /api/session với cookie 1
            System.out.println("5. Kiểm tra GET /api/session");
            HttpResponse sessRes1 = request("GET", baseUrl + "/api/session", null, cookie1);
            check(sessRes1.statusCode == 200, "GET /api/session phải trả về 200");
            Map<String, Object> sessMap1 = JsonHelper.parseObject(sessRes1.body);
            check(Boolean.TRUE.equals(sessMap1.get("authenticated")), "authenticated phải là true");
            Map<?, ?> userMap1 = (Map<?, ?>) sessMap1.get("user");
            check(user1.equalsIgnoreCase(userMap1.get("username").toString()), "username trong session phải khớp user1");
            System.out.println("   -> Xác minh phiên thành công cho người dùng: " + userMap1.get("displayName"));

            // 6. Đăng ký và Đăng nhập người dùng thứ 2 (Multi-Session Isolation)
            System.out.println("6. Kiểm tra Multi-Session: Đăng ký & Đăng nhập người dùng thứ 2 độc lập");
            String regBody2 = JsonHelper.toJson(Map.of(
                    "username", user2,
                    "password", pass2,
                    "displayName", display2
            ));
            HttpResponse regRes2 = request("POST", baseUrl + "/api/register", regBody2, null);
            check(regRes2.statusCode == 200, "Đăng ký user2 phải trả về 200");

            String correctLoginBody2 = JsonHelper.toJson(Map.of(
                    "username", user2,
                    "password", pass2
            ));
            HttpResponse loginRes2 = request("POST", baseUrl + "/api/login", correctLoginBody2, null);
            check(loginRes2.statusCode == 200, "Đăng nhập user2 phải trả về 200");
            String cookie2 = extractSessionCookie(loginRes2.headers);
            check(cookie2 != null && !cookie2.equals(cookie1), "Cookie phiên user2 phải khác cookie user1");

            // Kiểm tra phân tách phiên: cookie1 trả về user1, cookie2 trả về user2
            HttpResponse checkUser1 = request("GET", baseUrl + "/api/session", null, cookie1);
            HttpResponse checkUser2 = request("GET", baseUrl + "/api/session", null, cookie2);
            Map<?, ?> u1Data = (Map<?, ?>) JsonHelper.parseObject(checkUser1.body).get("user");
            Map<?, ?> u2Data = (Map<?, ?>) JsonHelper.parseObject(checkUser2.body).get("user");

            check(user1.equalsIgnoreCase(u1Data.get("username").toString()), "Session 1 phải là user1");
            check(user2.equalsIgnoreCase(u2Data.get("username").toString()), "Session 2 phải là user2");
            System.out.println("   -> Phân tách 2 phiên độc lập hoàn toàn: " + u1Data.get("username") + " & " + u2Data.get("username"));

            // 7. Kiểm tra Đăng xuất user1
            System.out.println("7. Kiểm tra Đăng xuất user1");
            HttpResponse logoutRes1 = request("POST", baseUrl + "/api/logout", null, cookie1);
            check(logoutRes1.statusCode == 200, "POST /api/logout phải trả về 200");

            HttpResponse checkAfterLogout1 = request("GET", baseUrl + "/api/session", null, cookie1);
            check(Boolean.FALSE.equals(JsonHelper.parseObject(checkAfterLogout1.body).get("authenticated")),
                    "Session 1 sau khi logout phải có authenticated=false");

            // Session 2 vẫn phải còn hiệu lực!
            HttpResponse checkUser2StillAlive = request("GET", baseUrl + "/api/session", null, cookie2);
            check(Boolean.TRUE.equals(JsonHelper.parseObject(checkUser2StillAlive.body).get("authenticated")),
                    "Session 2 vẫn phải hoạt động bình thường sau khi Session 1 logout");
            System.out.println("   -> Đăng xuất session 1 thành công mà không ảnh hưởng session 2!");

            System.out.println("=== TẤT CẢ CÁC BƯỚC END-TO-END ĐỀU PASS THÀNH CÔNG RỰC RỠ! ===");

        } finally {
            bridge.stop();
        }
    }

    private static String extractSessionCookie(Map<String, List<String>> headers) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                for (String sc : entry.getValue()) {
                    if (sc.contains(UiBridgeServer.SESSION_COOKIE_NAME + "=")) {
                        return sc;
                    }
                }
            }
        }
        return null;
    }

    private record HttpResponse(int statusCode, String body, Map<String, List<String>> headers) {}

    private static HttpResponse request(String method, String urlStr, String body, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(12000);
        conn.setInstanceFollowRedirects(false);

        if ("POST".equalsIgnoreCase(method)) {
            URI uri = URI.create(urlStr);
            conn.setRequestProperty("Origin", uri.getScheme() + "://" + uri.getRawAuthority());
        }

        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = (is != null) ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        return new HttpResponse(code, responseBody, conn.getHeaderFields());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
