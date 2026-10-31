package com.discordmini.client.bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kiểm thử tự động toàn diện cho UiBridgeServer:
 * 1. Khởi động bridge trên localhost khi chưa đăng nhập và khi ChatServer chưa chạy.
 * 2. Endpoint /api/status trả về đúng thông tin kết nối và serverOnline=false.
 * 3. Endpoint /api/session trả về authenticated=false khi chưa có cookie.
 * 4. Endpoint /api/register kiểm tra dữ liệu đầu vào (rỗng, ký tự cấm '|').
 * 5. Endpoint /api/register và /api/login trả về mã 503 với thông báo rõ ràng khi ChatServer chưa chạy.
 * 6. Các endpoint dữ liệu riêng tư (/api/state, /api/events, /api/rooms/create...) chặn 401 nếu chưa đăng nhập.
 * 7. Kiểm tra cơ chế SPA static fallback phục vụ index.html.
 */
public class UiBridgeServerTest {

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        System.out.println("Bắt đầu UiBridgeServerTest...");
        UiBridgeServer server = new UiBridgeServer(18080, "127.0.0.1", 59999); // Cổng 59999 không có ChatServer
        server.start();

        int port = server.getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        try {
            testStatusEndpointWhenServerOffline(baseUrl);
            testSessionEndpointUnauthenticated(baseUrl);
            testRegisterValidationEmptyFields(baseUrl);
            testRegisterValidationForbiddenChars(baseUrl);
            testCorsAndCsrfOriginValidation(baseUrl);
            testRegisterWhenServerOffline(baseUrl);
            testLoginWhenServerOffline(baseUrl);
            testProtectedEndpointsRejectUnauthenticated(baseUrl);
            testStaticSpaFallback(baseUrl);

            System.out.println("UiBridgeServerTest: TẤT CẢ TEST ĐỀU PASS! (100% THÀNH CÔNG)");
        } finally {
            server.stop();
        }
    }

    private static void testStatusEndpointWhenServerOffline(String baseUrl) throws Exception {
        HttpResponse res = request("GET", baseUrl + "/api/status", null, null);
        check(res.statusCode == 200, "GET /api/status phải trả về 200, nhận: " + res.statusCode);
        Map<String, Object> map = JsonHelper.parseObject(res.body);
        check(Boolean.FALSE.equals(map.get("serverOnline")), "serverOnline phải là false khi ChatServer tắt");
        System.out.println("  PASS: testStatusEndpointWhenServerOffline");
    }

    private static void testSessionEndpointUnauthenticated(String baseUrl) throws Exception {
        HttpResponse res = request("GET", baseUrl + "/api/session", null, null);
        check(res.statusCode == 200, "GET /api/session phải trả về 200");
        Map<String, Object> map = JsonHelper.parseObject(res.body);
        check(Boolean.FALSE.equals(map.get("authenticated")), "authenticated phải là false khi chưa có cookie");
        System.out.println("  PASS: testSessionEndpointUnauthenticated");
    }

    private static void testRegisterValidationEmptyFields(String baseUrl) throws Exception {
        String body = "{\"username\":\"\",\"password\":\"123\",\"displayName\":\"Test\"}";
        HttpResponse res = request("POST", baseUrl + "/api/register", body, null);
        check(res.statusCode == 400, "Đăng ký rỗng username phải trả về 400, nhận: " + res.statusCode);
        Map<String, Object> map = JsonHelper.parseObject(res.body);
        check(Boolean.FALSE.equals(map.get("success")), "success phải là false");
        check(map.get("message").toString().contains("trống"), "Thông báo lỗi phải nhắc tên đăng nhập trống");
        System.out.println("  PASS: testRegisterValidationEmptyFields");
    }

    private static void testRegisterValidationForbiddenChars(String baseUrl) throws Exception {
        String body = "{\"username\":\"user|bad\",\"password\":\"pass123\",\"displayName\":\"Test\"}";
        HttpResponse res = request("POST", baseUrl + "/api/register", body, null);
        check(res.statusCode == 400, "Đăng ký chứa '|' phải trả về 400");
        Map<String, Object> map = JsonHelper.parseObject(res.body);
        check(map.get("message").toString().contains("ký tự đặc biệt"), "Thông báo lỗi phải cảnh báo ký tự '|'");
        System.out.println("  PASS: testRegisterValidationForbiddenChars");
    }

    private static void testCorsAndCsrfOriginValidation(String baseUrl) throws Exception {
        HttpResponse rejected = requestWithOrigin("POST", baseUrl + "/api/register",
                "{}", "http://localhost.attacker:5173");
        check(rejected.statusCode == 403, "Origin giả mạo phải bị từ chối");
        check(!rejected.headers.containsKey("Access-control-allow-origin"),
                "CORS không được phản chiếu Origin giả mạo");
        HttpResponse protectedRejected = requestWithOrigin("POST", baseUrl + "/api/rooms/create",
                "{}", "http://localhost.attacker:5173");
        check(protectedRejected.statusCode == 403,
                "Origin giả mạo phải bị chặn trước mọi endpoint POST có session");
        HttpResponse unapprovedPort = requestWithOrigin("POST", baseUrl + "/api/register",
                "{}", "http://localhost:9009");
        check(unapprovedPort.statusCode == 403,
                "Origin localhost trên cổng không được cho phép phải bị từ chối");
        check(!unapprovedPort.headers.containsKey("Access-control-allow-origin"),
                "CORS không được cho phép localhost trên cổng không được cho phép");

        HttpResponse accepted = requestWithOrigin("POST", baseUrl + "/api/register",
                "{}", "http://localhost:5173");
        check(accepted.statusCode == 400, "Origin loopback hợp lệ phải tới bước kiểm tra payload");
        check("http://localhost:5173".equals(accepted.headers.get("Access-control-allow-origin").get(0)),
                "CORS phải chỉ phản chiếu Origin loopback được phép");
        HttpResponse sameOrigin = requestWithOrigin("POST", baseUrl + "/api/register", "{}", baseUrl);
        check(sameOrigin.statusCode == 400, "Origin cùng cổng bridge phải được cho phép");
        check(baseUrl.equals(sameOrigin.headers.get("Access-control-allow-origin").get(0)),
                "CORS phải cho phép đúng origin của bridge");
        System.out.println("  PASS: testCorsAndCsrfOriginValidation");
    }

    private static void testRegisterWhenServerOffline(String baseUrl) throws Exception {
        String body = "{\"username\":\"valid_user\",\"password\":\"pass123\",\"displayName\":\"Người dùng thử\"}";
        HttpResponse res = request("POST", baseUrl + "/api/register", body, null);
        check(res.statusCode == 503, "Đăng ký khi ChatServer chưa chạy phải trả về 503, nhận: " + res.statusCode);
        Map<String, Object> map = JsonHelper.parseObject(res.body);
        check(Boolean.FALSE.equals(map.get("success")), "success phải là false");
        check(map.get("message").toString().contains("Không thể kết nối đến ChatServer"), "Thông báo phải chỉ rõ không kết nối được ChatServer");
        System.out.println("  PASS: testRegisterWhenServerOffline");
    }

    private static void testLoginWhenServerOffline(String baseUrl) throws Exception {
        String body = "{\"username\":\"valid_user\",\"password\":\"pass123\"}";
        HttpResponse res = request("POST", baseUrl + "/api/login", body, null);
        check(res.statusCode == 503, "Đăng nhập khi ChatServer chưa chạy phải trả về 503, nhận: " + res.statusCode);
        Map<String, Object> map = JsonHelper.parseObject(res.body);
        check(Boolean.FALSE.equals(map.get("success")), "success phải là false");
        check(map.get("message").toString().contains("Không thể kết nối đến ChatServer"), "Thông báo phải chỉ rõ không kết nối được ChatServer");
        System.out.println("  PASS: testLoginWhenServerOffline");
    }

    private static void testProtectedEndpointsRejectUnauthenticated(String baseUrl) throws Exception {
        String[] endpoints = new String[] {
                baseUrl + "/api/state",
                baseUrl + "/api/events",
                baseUrl + "/api/rooms/join",
                baseUrl + "/api/rooms/create",
                baseUrl + "/api/messages/send",
                baseUrl + "/api/friends/refresh"
        };
        for (String ep : endpoints) {
            String method = ep.contains("join") || ep.contains("create") || ep.contains("send") ? "POST" : "GET";
            String body = method.equals("POST") ? "{\"content\":\"hello\"}" : null;
            HttpResponse res = request(method, ep, body, null);
            check(res.statusCode == 401, "Endpoint " + ep + " chưa đăng nhập phải trả về 401, nhận: " + res.statusCode);
        }
        System.out.println("  PASS: testProtectedEndpointsRejectUnauthenticated");
    }

    private static void testStaticSpaFallback(String baseUrl) throws Exception {
        HttpResponse res = request("GET", baseUrl + "/channels/@me/unknown-route", null, null);
        check(res.statusCode == 200, "SPA fallback phải trả về 200");
        check(res.body.contains("<!DOCTYPE html>") || res.body.contains("<html"), "Nội dung phản hồi phải là HTML");
        System.out.println("  PASS: testStaticSpaFallback");
    }

    private record HttpResponse(int statusCode, String body, Map<String, java.util.List<String>> headers) {}

    private static HttpResponse request(String method, String urlStr, String body, String cookie) throws Exception {
        URI uri = URI.create(urlStr);
        return requestWithOrigin(method, urlStr, body,
                "POST".equalsIgnoreCase(method) ? uri.getScheme() + "://" + uri.getRawAuthority() : null,
                cookie);
    }

    private static HttpResponse requestWithOrigin(String method, String urlStr, String body, String origin) throws Exception {
        return requestWithOrigin(method, urlStr, body, origin, null);
    }

    private static HttpResponse requestWithOrigin(String method, String urlStr, String body, String origin, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(false);

        if (origin != null) {
            conn.setRequestProperty("Origin", origin);
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
