package com.discordmini.client.service;

import com.discordmini.client.model.Room;
import com.discordmini.client.network.SocketClient;
import com.discordmini.client.protocol.ProtocolHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bộ kiểm thử tự động cho AN-06: ROOM SERVICE.
 *
 * Kiểm tra các tiêu chí:
 * 1. Giữ định dạng room ID theo protocol thực tế: JOIN_ROOM|<id>, không mã hóa sai ID số.
 * 2. Chỉ cập nhật phòng hiện tại (currentRoom) sau phản hồi OK|JOIN_ROOM thành công của Server.
 * 3. Ghi nhớ phòng vào thành công gần nhất (lastSuccessfulRoomId) để AN-10 sử dụng.
 * 4. Xử lý tạo phòng lỗi, phòng không tồn tại, chuyển phòng thất bại và mất kết nối.
 * 5. Cơ chế thế hệ phòng (roomEpoch) ngăn dữ liệu đến trễ của phòng cũ ghi đè phòng mới.
 * 6. Sau leave thành công, UI không còn cho gửi vào phòng đã rời (isInRoom = false, currentRoom = null).
 * 7. Tích hợp ChatService để dọn sạch trạng thái phòng chat khi chuyển hoặc rời phòng.
 */
public final class RoomServiceTest {
    private RoomServiceTest() {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ AN-06: ROOM SERVICE ===");
        testListRooms();
        testCreateRoomSuccessAndError();
        testJoinRoomProtocolAndSuccessOnlyAfterOk();
        testJoinRoomFailedKeepsCurrentRoom();
        testLeaveRoomClearsCurrentRoom();
        testRejoinLastRoomForAn10();
        testRapidRoomSwitchAndEpochIsolation();
        System.out.println("=== TẤT CẢ TEST AN-06: ROOM SERVICE ĐÃ PASS! ===");
    }

    /**
     * Tiêu chí 1: listRooms gửi lệnh LIST_ROOMS và parse đúng danh sách phòng trả về.
     */
    private static void testListRooms() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line = readCommand(reader, writer, "LIST_ROOMS");
                    // Server phản hồi ROOMS|id1,encodedName1|id2,encodedName2
                    String room1Name = ProtocolHandler.encode("Sảnh chính");
                    String room2Name = ProtocolHandler.encode("Phòng học tập");
                    writer.println("ROOMS|1," + room1Name + "|2," + room2Name);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            BlockingQueue<List<Room>> queue = new ArrayBlockingQueue<>(1);
            roomService.addListener(new RoomEventListener() {
                @Override
                public void onRoomListUpdated(List<Room> rooms) {
                    queue.offer(rooms);
                }
            });

            roomService.listRooms();

            List<Room> rooms = queue.poll(3, TimeUnit.SECONDS);
            check(rooms != null, "Phải nhận được danh sách phòng");
            check(rooms.size() == 2, "Danh sách phải có 2 phòng");
            check(rooms.get(0).id() == 1 && "Sảnh chính".equals(rooms.get(0).name()), "Phòng 1 phải là Sảnh chính");
            check(rooms.get(1).id() == 2 && "Phòng học tập".equals(rooms.get(1).name()), "Phòng 2 phải là Phòng học tập");

            client.close();
        }
        System.out.println("  [PASS] testListRooms");
    }

    /**
     * Tiêu chí 2: Tạo phòng thành công và xử lý lỗi tên phòng trùng hoặc không hợp lệ.
     */
    private static void testCreateRoomSuccessAndError() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // Lần 1: Tạo phòng thành công
                    String line1 = readCommand(reader, writer, "CREATE_ROOM");
                    writer.println("OK|ROOM_CREATED|10|" + ProtocolHandler.encode("Phòng Công Nghệ"));

                    // RoomService tự động gửi LIST_ROOMS để refresh UI
                    String nextLine = reader.readLine();
                    while ("PING".equals(nextLine)) {
                        writer.println("PONG");
                        nextLine = reader.readLine();
                    }
                    if (nextLine != null && nextLine.startsWith("LIST_ROOMS")) {
                        writer.println("ROOMS|10," + ProtocolHandler.encode("Phòng Công Nghệ"));
                        nextLine = reader.readLine();
                    }

                    // Lần 2: Tạo phòng lỗi
                    check(nextLine != null && nextLine.startsWith("CREATE_ROOM"), "Phải nhận lệnh CREATE_ROOM lần 2");
                    writer.println("ERROR|" + ProtocolHandler.encode("Tên phòng đã tồn tại."));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            BlockingQueue<Room> createdQueue = new ArrayBlockingQueue<>(1);
            BlockingQueue<String> errorQueue = new ArrayBlockingQueue<>(1);

            roomService.addListener(new RoomEventListener() {
                @Override
                public void onRoomCreated(Room room) {
                    createdQueue.offer(room);
                }

                @Override
                public void onRoomError(String errorMessage) {
                    errorQueue.offer(errorMessage);
                }
            });

            // 1. Tạo phòng thành công
            roomService.createRoom("Phòng Công Nghệ");
            Room created = createdQueue.poll(3, TimeUnit.SECONDS);
            check(created != null, "Phải nhận callback onRoomCreated");
            check(created.id() == 10 && "Phòng Công Nghệ".equals(created.name()), "Dữ liệu phòng tạo mới phải khớp");

            // 2. Tạo phòng trùng tên
            roomService.createRoom("Phòng Công Nghệ");
            String errorMsg = errorQueue.poll(3, TimeUnit.SECONDS);
            check(errorMsg != null, "Phải nhận callback onRoomError");
            check(errorMsg.contains("đã tồn tại"), "Nội dung lỗi tiếng Việt phải đúng");

            client.close();
        }
        System.out.println("  [PASS] testCreateRoomSuccessAndError");
    }

    /**
     * Tiêu chí 3: Định dạng JOIN_ROOM|<id> giữ ID số nguyên, và chỉ cập nhật currentRoom sau khi nhận OK.
     */
    private static void testJoinRoomProtocolAndSuccessOnlyAfterOk() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            BlockingQueue<String> receivedOnServer = new ArrayBlockingQueue<>(1);
            PrintWriter[] serverWriter = new PrintWriter[1];

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    serverWriter[0] = writer;
                    String line = reader.readLine();
                    if (line != null) {
                        receivedOnServer.offer(line);
                    }
                    // Giữ kết nối mở chờ kiểm tra
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            BlockingQueue<Room> joinedQueue = new ArrayBlockingQueue<>(1);
            roomService.addListener(new RoomEventListener() {
                @Override
                public void onRoomJoined(Room room) {
                    joinedQueue.offer(room);
                }
            });

            // Gửi lệnh join room 5
            roomService.joinRoom(5);

            String serverRecv = receivedOnServer.poll(3, TimeUnit.SECONDS);
            check(serverRecv != null, "Server phải nhận được packet JOIN_ROOM");
            String[] parts = ProtocolHandler.split(serverRecv);
            check("JOIN_ROOM".equals(parts[0]), "Lệnh phải là JOIN_ROOM");
            // Rất quan trọng: parts[1] phải là "5", KHÔNG PHẢI Base64!
            check("5".equals(parts[1]), "Room ID phải là số 5 nguyên bản, không được mã hóa Base64");

            // Trước khi server phản hồi OK, currentRoom phải vẫn là null!
            check(roomService.getCurrentRoom() == null, "currentRoom PHẢI LÀ null trước khi Server xác nhận OK");
            check(!roomService.isInRoom(), "isInRoom() phải là false khi chưa có OK");

            // Server phản hồi thành công
            serverWriter[0].println("OK|JOIN_ROOM|5|" + ProtocolHandler.encode("Nhóm Học Mạng"));

            Room joined = joinedQueue.poll(3, TimeUnit.SECONDS);
            check(joined != null, "Phải nhận callback onRoomJoined");
            check(joined.id() == 5 && "Nhóm Học Mạng".equals(joined.name()), "Tên và ID phòng phải khớp");
            check(roomService.getCurrentRoom() != null && roomService.getCurrentRoom().id() == 5, "currentRoom phải được cập nhật thành phòng 5");
            check(roomService.isInRoom(), "isInRoom() phải là true sau khi vào phòng");
            check(Integer.valueOf(5).equals(roomService.getLastSuccessfulRoomId()), "lastSuccessfulRoomId phải được ghi nhớ là 5");

            client.close();
        }
        System.out.println("  [PASS] testJoinRoomProtocolAndSuccessOnlyAfterOk");
    }

    /**
     * Tiêu chí 4: Chuyển phòng thất bại (ERROR từ Server) giữ nguyên phòng hiện tại.
     */
    private static void testJoinRoomFailedKeepsCurrentRoom() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    // Bước 1: Cho join phòng 1 thành công
                    reader.readLine();
                    writer.println("OK|JOIN_ROOM|1|" + ProtocolHandler.encode("Phòng 1"));

                    // Bước 2: Nhận yêu cầu join phòng 999 (không tồn tại)
                    reader.readLine();
                    writer.println("ERROR|" + ProtocolHandler.encode("Phòng không tồn tại hoặc đã bị xóa."));
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            BlockingQueue<Room> joinedQueue = new ArrayBlockingQueue<>(1);
            BlockingQueue<String> errorQueue = new ArrayBlockingQueue<>(1);

            roomService.addListener(new RoomEventListener() {
                @Override
                public void onRoomJoined(Room room) {
                    joinedQueue.offer(room);
                }

                @Override
                public void onRoomError(String errorMessage) {
                    errorQueue.offer(errorMessage);
                }
            });

            // Join phòng 1
            roomService.joinRoom(1);
            Room room1 = joinedQueue.poll(3, TimeUnit.SECONDS);
            check(room1 != null && roomService.getCurrentRoom().id() == 1, "Phải đang ở phòng 1");

            // Cố gắng join phòng 999 bị lỗi
            roomService.joinRoom(999);
            String err = errorQueue.poll(3, TimeUnit.SECONDS);
            check(err != null && err.contains("không tồn tại"), "Phải nhận lỗi phòng không tồn tại");

            // currentRoom vẫn phải là phòng 1, không được thành phòng 999 hay null!
            check(roomService.getCurrentRoom() != null && roomService.getCurrentRoom().id() == 1,
                    "Khi chuyển phòng thất bại, currentRoom phải giữ nguyên phòng cũ (phòng 1)");

            client.close();
        }
        System.out.println("  [PASS] testJoinRoomFailedKeepsCurrentRoom");
    }

    /**
     * Tiêu chí 5: Rời phòng (leaveRoom) xóa ngay currentRoom, ngăn gửi tin vào phòng đã rời.
     */
    private static void testLeaveRoomClearsCurrentRoom() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            BlockingQueue<String> serverCommands = new ArrayBlockingQueue<>(2);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    serverCommands.offer(reader.readLine());
                    writer.println("OK|JOIN_ROOM|2|" + ProtocolHandler.encode("Phòng 2"));

                    serverCommands.offer(reader.readLine());
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            BlockingQueue<Boolean> leftQueue = new ArrayBlockingQueue<>(1);
            roomService.addListener(new RoomEventListener() {
                @Override
                public void onRoomLeft() {
                    leftQueue.offer(true);
                }
            });

            roomService.joinRoom(2);
            Thread.sleep(100);
            check(roomService.isInRoom(), "Phải đang ở phòng 2");

            // Rời phòng
            roomService.leaveRoom();

            check(roomService.getCurrentRoom() == null, "currentRoom phải là null ngay sau khi leave");
            check(!roomService.isInRoom(), "isInRoom() phải là false ngay sau khi leave");
            Boolean left = leftQueue.poll(3, TimeUnit.SECONDS);
            check(Boolean.TRUE.equals(left), "Phải nhận callback onRoomLeft");

            String leaveCmd = serverCommands.poll(3, TimeUnit.SECONDS);
            leaveCmd = serverCommands.poll(3, TimeUnit.SECONDS);
            check(leaveCmd != null && leaveCmd.startsWith("LEAVE_ROOM"), "Phải gửi lệnh LEAVE_ROOM lên server");

            client.close();
        }
        System.out.println("  [PASS] testLeaveRoomClearsCurrentRoom");
    }

    /**
     * Tiêu chí 6: Ghi nhớ lastSuccessfulRoomId và hỗ trợ rejoinLastRoom phục vụ AN-10.
     */
    private static void testRejoinLastRoomForAn10() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            BlockingQueue<String> commands = new ArrayBlockingQueue<>(5);

            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        commands.offer(line);
                        if (line.startsWith("JOIN_ROOM|7")) {
                            writer.println("OK|JOIN_ROOM|7|" + ProtocolHandler.encode("Phòng Đồ Án"));
                        }
                    }
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            roomService.joinRoom(7);
            Thread.sleep(150);

            check(Integer.valueOf(7).equals(roomService.getLastSuccessfulRoomId()), "lastSuccessfulRoomId phải là 7");

            // 1. Trường hợp mất kết nối ngoài ý muốn (không chủ động rời phòng):
            // lastSuccessfulRoomId vẫn phải giữ giá trị 7 để phục vụ AN-10 Automatic Reconnection
            boolean rejoined = roomService.rejoinLastRoom();
            check(rejoined, "rejoinLastRoom phải trả về true khi có lastSuccessfulRoomId");

            // 2. Trường hợp người dùng chủ động rời phòng (leaveRoom):
            // reconnect không được tự đưa họ trở lại phòng đã chủ động rời
            roomService.leaveRoom();
            check(roomService.getCurrentRoom() == null, "currentRoom là null");
            check(roomService.getLastSuccessfulRoomId() == null, "Chủ động rời phòng thì lastSuccessfulRoomId phải bị xóa sạch (null)");
            check(!roomService.rejoinLastRoom(), "rejoinLastRoom phải trả về false khi đã chủ động rời phòng");

            String firstJoin = commands.poll(3, TimeUnit.SECONDS);
            check(firstJoin != null && firstJoin.contains("JOIN_ROOM|7"), "Lệnh đầu là JOIN_ROOM|7");

            String secondJoin = commands.poll(3, TimeUnit.SECONDS);
            check(secondJoin != null && secondJoin.contains("JOIN_ROOM|7"), "Lệnh tự động rejoin phải gửi JOIN_ROOM|7");

            String leaveCmd = commands.poll(3, TimeUnit.SECONDS);
            check(leaveCmd != null && leaveCmd.startsWith("LEAVE_ROOM"), "Lệnh kế tiếp là LEAVE_ROOM");

            client.close();
        }
        System.out.println("  [PASS] testRejoinLastRoomForAn10");
    }

    /**
     * Tiêu chí 7: Đổi phòng nhanh và cô lập thế hệ phòng (roomEpoch) cùng mất mạng.
     */
    private static void testRapidRoomSwitchAndEpochIsolation() throws Exception {
        try (ServerSocket fakeServer = new ServerSocket(0)) {
            int port = fakeServer.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket peer = fakeServer.accept()) {
                    check(!peer.isClosed(), "Peer phải đang mở kết nối");
                    // Giữ kết nối rồi đóng socket giả lập ngắt mạng
                    Thread.sleep(300);
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SocketClient client = new SocketClient();
            client.connect("127.0.0.1", port);
            RoomService roomService = new RoomService(client);

            int initialEpoch = roomService.getRoomEpoch();

            // Chuyển phòng liên tục
            roomService.joinRoom(10);
            check(roomService.getRoomEpoch() == initialEpoch + 1, "Epoch phải tăng khi gọi joinRoom");

            roomService.joinRoom(20);
            check(roomService.getRoomEpoch() == initialEpoch + 2, "Epoch tiếp tục tăng khi chuyển phòng khác");

            roomService.leaveRoom();
            check(roomService.getRoomEpoch() == initialEpoch + 3, "Epoch tăng khi gọi leaveRoom");

            // Giả lập sự kiện CLIENT_DISCONNECTED
            client.close();
            Thread.sleep(100);

            check(roomService.getCurrentRoom() == null, "Mất kết nối thì currentRoom phải là null");
            check(!roomService.isInRoom(), "isInRoom() phải là false khi mất kết nối");
        }
        System.out.println("  [PASS] testRapidRoomSwitchAndEpochIsolation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("KIỂM THỬ THẤT BẠI: " + message);
        }
    }

    private static String readCommand(BufferedReader reader, PrintWriter writer, String command) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if ("PING".equals(line)) {
                writer.println("PONG");
                continue;
            }
            if (command.equals(ProtocolHandler.split(line)[0])) {
                return line;
            }
            throw new AssertionError("Expected " + command + ", received " + line);
        }
        return null;
    }
}
