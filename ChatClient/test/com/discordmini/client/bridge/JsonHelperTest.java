package com.discordmini.client.bridge;

import java.util.List;
import java.util.Map;

public class JsonHelperTest {
    public static void main(String[] args) {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ JSON HELPER ===");
        testSerializeAndParsePrimitives();
        testSpecialCharactersAndVietnameseAndEmoji();
        testNestedObjectsAndArrays();
        System.out.println("=== TẤT CẢ TEST JSON HELPER ĐÃ PASS! ===");
    }

    private static void testSerializeAndParsePrimitives() {
        Map<String, Object> input = Map.of(
                "name", "an_tong",
                "age", 20,
                "active", true
        );
        String json = JsonHelper.toJson(input);
        check(json.contains("\"name\":\"an_tong\""), "json phải chứa name");
        check(json.contains("\"age\":20"), "json phải chứa age");
        check(json.contains("\"active\":true"), "json phải chứa active");

        Map<String, Object> parsed = JsonHelper.parseObject(json);
        check("an_tong".equals(parsed.get("name")), "parsed name phải đúng");
        check(Integer.valueOf(20).equals(parsed.get("age")), "parsed age phải đúng");
        check(Boolean.TRUE.equals(parsed.get("active")), "parsed active phải đúng");
        System.out.println("  [PASS] testSerializeAndParsePrimitives");
    }

    private static void testSpecialCharactersAndVietnameseAndEmoji() {
        String complex = "Xin chào: \"Bảo An\" / 'Test' \\n \n \t \r 🎉 🚀 Tiếng Việt có dấu: ă â đ ê ô ơ ư";
        Map<String, Object> map = Map.of("content", complex);
        String json = JsonHelper.toJson(map);

        Map<String, Object> parsed = JsonHelper.parseObject(json);
        check(complex.equals(parsed.get("content")), "Nội dung tiếng Việt, emoji, ký tự đặc biệt phải được bảo toàn nguyên vẹn sau parse");
        System.out.println("  [PASS] testSpecialCharactersAndVietnameseAndEmoji");
    }

    private static void testNestedObjectsAndArrays() {
        Map<String, Object> inner = Map.of("id", 1, "roomName", "Phòng 1");
        Map<String, Object> root = Map.of(
                "success", true,
                "data", inner,
                "items", List.of("A", "B", 123)
        );
        String json = JsonHelper.toJson(root);

        Map<String, Object> parsed = JsonHelper.parseObject(json);
        check(Boolean.TRUE.equals(parsed.get("success")), "success = true");
        Object dataObj = parsed.get("data");
        check(dataObj instanceof Map<?, ?>, "data phải là Map");
        Map<?, ?> dataMap = (Map<?, ?>) dataObj;
        check("Phòng 1".equals(dataMap.get("roomName")), "roomName phải là 'Phòng 1'");

        Object itemsObj = parsed.get("items");
        check(itemsObj instanceof List<?>, "items phải là List");
        List<?> itemsList = (List<?>) itemsObj;
        check(itemsList.size() == 3, "items size phải là 3");
        check("A".equals(itemsList.get(0)), "item 0 phải là 'A'");
        System.out.println("  [PASS] testNestedObjectsAndArrays");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("JSON HELPER TEST FAILED: " + message);
        }
    }
}
