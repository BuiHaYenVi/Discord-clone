Được. Nếu bạn đã **chốt đề tài IV.1 – “Ứng dụng Chat Nhóm (Group Chat) với Quản lý Phòng và Lịch sử Tin nhắn”**, mình khuyên triển khai theo hướng **Java Client–Server sử dụng TCP Socket**, làm bằng **NetBeans**.

Mục tiêu là: **đủ chức năng để giống một Discord Mini**, nhưng phần lõi vẫn tập trung vào kiến thức **Lập trình mạng máy tính**.

---

# 1. Định hướng tổng thể

Tên đề tài có thể đặt:

> **XÂY DỰNG ỨNG DỤNG CHAT NHÓM ĐA NGƯỜI DÙNG VỚI QUẢN LÝ PHÒNG VÀ LỊCH SỬ TIN NHẮN SỬ DỤNG TCP SOCKET**

Mô hình:

```text
                    ┌──────────────────────┐
                    │      DATABASE        │
                    │                      │
                    │ users                │
                    │ rooms                │
                    │ room_members         │
                    │ messages             │
                    └──────────▲───────────┘
                               │ JDBC
                               │
                    ┌──────────┴───────────┐
                    │      CHAT SERVER      │
                    │       Java            │
                    │                       │
                    │ ServerSocket          │
                    │ ClientHandler         │
                    │ RoomManager           │
                    │ UserManager            │
                    │ MessageManager        │
                    └───────▲──────▲────────┘
                            │      │
                       TCP Socket  TCP Socket
                            │      │
                  ┌─────────┘      └─────────┐
                  │                          │
          ┌───────┴───────┐          ┌───────┴───────┐
          │    CLIENT 1   │          │    CLIENT 2   │
          │     Java      │          │     Java      │
          │      GUI      │          │      GUI      │
          └───────────────┘          └───────────────┘
```

Ví dụ:

**Client A** gửi:

> Xin chào mọi người!

→ TCP Socket → Server

→ Server xác định Client A đang ở phòng `Gaming`

→ Server gửi tin nhắn cho tất cả thành viên phòng `Gaming`.

---

# 2. Chức năng nên chia thành 3 mức

Mình **không khuyên làm quá nhiều chức năng ngay từ đầu**.

## 🔴 Mức 1 – Bắt buộc

Đây là phần phải hoàn thành.

### Tài khoản

- Đăng ký
- Đăng nhập
- Đăng xuất

### Chat

- Kết nối Server
- Chat nhóm
- Gửi tin nhắn realtime
- Nhiều Client cùng chat
- Hiển thị người gửi
- Hiển thị thời gian

### Phòng

- Danh sách phòng
- Tạo phòng
- Tham gia phòng
- Rời phòng
- Chuyển phòng

### Lịch sử

- Server lưu tin nhắn
- Client vào phòng → tải lịch sử
- Hiển thị lịch sử chat

### Online

- Danh sách người đang online
- Thông báo:

```text
Vinh đã tham gia phòng.
Nam đã rời phòng.
```

---

# 3. 🟡 Mức 2 – Nên làm

Sau khi mức 1 chạy ổn:

- Chat riêng 1-1
- Gửi hình ảnh
- Gửi file
- Emoji
- Reply tin nhắn
- Xóa tin nhắn
- Sửa tin nhắn
- Tìm kiếm tin nhắn

---

# 4. 🟢 Mức 3 – Nếu còn thời gian

Để tạo điểm nhấn giống Discord:

- Avatar
- Bạn bè
- Server
- Channel
- Phân quyền Admin/Member
- Voice chat
- Notification
- Dark mode

**Voice chat nên để cuối cùng.** Không nên bắt đầu bằng voice/video vì nó sẽ làm đồ án phức tạp lên rất nhiều.

---

# 5. Kiến trúc project

Mình đề xuất **tách Server và Client thành 2 project NetBeans riêng**.

```text
DiscordMini/
│
├── ChatServer/
│
└── ChatClient/
```

Đây là cách dễ hiểu nhất khi bảo vệ.

---

# 6. 📁 Cấu trúc ChatServer

```text
ChatServer/
│
├── src/
│   └── com.discordmini.server/
│
│       ├── ServerApplication.java
│       │
│       ├── network/
│       │   ├── ChatServer.java
│       │   ├── ClientHandler.java
│       │   └── ClientConnection.java
│       │
│       ├── manager/
│       │   ├── ClientManager.java
│       │   ├── RoomManager.java
│       │   └── UserManager.java
│       │
│       ├── model/
│       │   ├── User.java
│       │   ├── Room.java
│       │   ├── Message.java
│       │   └── RoomMember.java
│       │
│       ├── protocol/
│       │   ├── MessageType.java
│       │   ├── Request.java
│       │   ├── Response.java
│       │   └── ProtocolHandler.java
│       │
│       ├── dao/
│       │   ├── DatabaseConnection.java
│       │   ├── UserDAO.java
│       │   ├── RoomDAO.java
│       │   └── MessageDAO.java
│       │
│       └── service/
│           ├── AuthService.java
│           ├── ChatService.java
│           └── RoomService.java
│
└── lib/
```

---

# 7. 📁 Cấu trúc ChatClient

```text
ChatClient/
│
├── src/
│   └── com.discordmini.client/
│
│       ├── ClientApplication.java
│       │
│       ├── network/
│       │   ├── SocketClient.java
│       │   ├── ServerListener.java
│       │   └── ConnectionManager.java
│       │
│       ├── model/
│       │   ├── User.java
│       │   ├── Room.java
│       │   └── Message.java
│       │
│       ├── protocol/
│       │   ├── MessageType.java
│       │   ├── Request.java
│       │   ├── Response.java
│       │   └── ProtocolHandler.java
│       │
│       ├── service/
│       │   ├── AuthService.java
│       │   ├── ChatService.java
│       │   └── RoomService.java
│       │
│       └── ui/
│           ├── LoginFrame.java
│           ├── RegisterFrame.java
│           ├── MainFrame.java
│           ├── ChatPanel.java
│           ├── RoomPanel.java
│           ├── UserPanel.java
│           └── MessagePanel.java
│
└── lib/
```

---

# 8. Tại sao phải chia thư mục như vậy?

Đây là phần rất quan trọng khi làm đồ án.

### `network/`

Chịu trách nhiệm:

> **Kết nối mạng**

Ví dụ:

```java
Socket socket;
ServerSocket serverSocket;
InputStream input;
OutputStream output;
```

---

### `protocol/`

Quy định:

> **Client và Server nói chuyện với nhau như thế nào.**

Ví dụ:

```text
LOGIN
REGISTER
JOIN_ROOM
LEAVE_ROOM
SEND_MESSAGE
GET_HISTORY
CREATE_ROOM
```

---

### `model/`

Chứa đối tượng dữ liệu:

```text
User
Room
Message
RoomMember
```

---

### `manager/`

Server quản lý các Client và phòng:

```text
ClientManager
RoomManager
UserManager
```

Ví dụ:

```text
RoomManager
     │
     ├── General
     │     ├── Vinh
     │     └── Nam
     │
     ├── Gaming
     │     ├── An
     │     └── Minh
     │
     └── Study
           └── Vinh
```

---

### `dao/`

Làm việc với Database:

```text
UserDAO
RoomDAO
MessageDAO
```

Ví dụ:

```text
ChatService
     ↓
MessageDAO
     ↓
PostgreSQL
```

---

### `service/`

Xử lý nghiệp vụ.

Ví dụ:

```text
Client gửi LOGIN
       ↓
AuthService
       ↓
UserDAO
       ↓
Database
       ↓
LOGIN_SUCCESS
```

---

# 9. Database nên thiết kế như thế nào?

Nếu bạn đã quen **PostgreSQL**, mình khuyên tiếp tục dùng PostgreSQL.

Có thể bắt đầu với **4 bảng chính**:

```text
users
rooms
room_members
messages
```

### `users`

```text
id
username
password
display_name
avatar
status
created_at
```

### `rooms`

```text
id
name
description
created_by
created_at
```

### `room_members`

```text
id
room_id
user_id
joined_at
```

### `messages`

```text
id
room_id
user_id
content
message_type
created_at
```

Quan hệ:

```text
USER
 │
 ├──────────────┐
 │              │
 ▼              ▼
room_members   messages
 │              │
 ▼              ▼
ROOM ◄──────── MESSAGE
```

---

# 10. Giao thức Client ↔ Server

Đây là phần mình khuyên bạn **đầu tư nhiều nhất** vì nó liên quan trực tiếp đến môn học.

Bạn có thể tự xây dựng protocol bằng **JSON**.

Ví dụ Client đăng nhập:

```json
{
  "type": "LOGIN",
  "username": "vinh",
  "password": "123456"
}
```

Server trả:

```json
{
  "type": "LOGIN_SUCCESS",
  "userId": 1,
  "username": "vinh"
}
```

---

### Gửi tin nhắn

Client:

```json
{
  "type": "SEND_MESSAGE",
  "roomId": 10,
  "content": "Xin chào mọi người!"
}
```

Server broadcast:

```json
{
  "type": "NEW_MESSAGE",
  "roomId": 10,
  "sender": "Vinh",
  "content": "Xin chào mọi người!",
  "timestamp": "2026-09-08 16:00:00"
}
```

---

# 11. Các loại Request nên có

Bạn có thể quy định khoảng:

```text
LOGIN
REGISTER
LOGOUT

GET_ROOMS
CREATE_ROOM
JOIN_ROOM
LEAVE_ROOM

SEND_MESSAGE
GET_HISTORY

GET_ONLINE_USERS

SEND_PRIVATE_MESSAGE
```

Server xử lý:

```text
Client
  │
  │ Request
  ▼
ClientHandler
  │
  ├── LOGIN ───────→ AuthService
  │
  ├── SEND_MESSAGE → ChatService
  │
  ├── JOIN_ROOM ───→ RoomService
  │
  └── GET_HISTORY ─→ ChatService
```

---

# 12. Phần quan trọng nhất: Multithreading

Đề tài của bạn có chữ:

> **Server Chat đa luồng**

Vì vậy Server phải xử lý **nhiều Client đồng thời**.

Ví dụ:

```text
                  SERVER
                    │
       ┌────────────┼────────────┐
       │            │            │
       ▼            ▼            ▼
 ClientHandler   ClientHandler  ClientHandler
       │            │            │
     Client A     Client B     Client C
```

Khi Client kết nối:

```java
Socket socket = serverSocket.accept();

ClientHandler client =
        new ClientHandler(socket);

new Thread(client).start();
```

Như vậy:

- Client A đang chat
- Client B đăng nhập
- Client C vào phòng

→ Server vẫn xử lý đồng thời.

**Đây chính là một điểm rất tốt để trình bày khi bảo vệ.**

---

# 13. Luồng hoạt động khi chat

Ví dụ Vinh và Nam cùng phòng `Gaming`.

```text
Vinh
 │
 │ "Hello"
 ▼
Client Vinh
 │
 │ TCP
 ▼
Chat Server
 │
 ├── Xác định Vinh
 ├── Xác định Room Gaming
 ├── Lưu Database
 │
 └── Broadcast
       │
       └──────► Nam
```

Nam nhận:

```text
Vinh
Hello
16:05
```

---

# 14. Khi người dùng vào phòng

Ví dụ Vinh click:

> Gaming

Client:

```text
JOIN_ROOM
roomId = 2
```

Server:

```text
RoomManager
     ↓
Kiểm tra phòng
     ↓
Thêm Vinh vào Room
     ↓
Database
     ↓
GET_HISTORY
     ↓
Gửi lịch sử cho Vinh
```

Giao diện:

```text
┌──────────────────────────────────────────────┐
│ Discord Mini                                │
├──────────┬───────────────┬───────────────────┤
│ ROOMS    │ # Gaming      │ MEMBERS           │
│          │               │                   │
│ General  │ Vinh: Hello   │ 🟢 Vinh           │
│ Gaming   │ Nam: Hi       │ 🟢 Nam            │
│ Study    │ An: Hey       │ 🟢 An             │
│          │               │                   │
│          │               │                   │
│          │               │                   │
│          ├───────────────┤                   │
│          │ Nhập tin nhắn │                   │
└──────────┴───────────────┴───────────────────┘
```

Giao diện này sẽ **rất giống Discord nhưng vẫn là đồ án của bạn**.

---

# 15. Công nghệ mình đề xuất

Nếu nhóm bạn làm Java:

### Backend

- Java
- TCP Socket
- ServerSocket
- Multithreading
- JDBC

### Client

Nếu muốn dễ:

> **Java Swing**

Nếu muốn giao diện đẹp hơn:

> **JavaFX**

Mình nghiêng về **JavaFX** nếu nhóm có đủ thời gian.

### Database

> **PostgreSQL**

### Format dữ liệu

> **JSON**

Thư viện có thể dùng:

> Jackson hoặc Gson

---

# 16. Lộ trình triển khai

Đừng code tất cả cùng lúc.

## Giai đoạn 1 – TCP Socket

Làm:

```text
Server
   ↕
Client
```

Client gửi:

```text
Hello Server
```

Server trả:

```text
Hello Client
```

**Chưa cần Database, chưa cần GUI đẹp.**

---

## Giai đoạn 2 – Multithreading

Cho:

```text
Client 1
Client 2
Client 3
Client 4
```

cùng kết nối.

Test:

```text
Client 1 → Server
Client 2 → Server
Client 3 → Server
```

---

## Giai đoạn 3 – Protocol

Xây dựng JSON:

```text
LOGIN
REGISTER
SEND_MESSAGE
JOIN_ROOM
LEAVE_ROOM
```

---

## Giai đoạn 4 – Database

Thêm:

```text
users
rooms
room_members
messages
```

---

## Giai đoạn 5 – Chat nhóm

Làm:

```text
Room
 ↓
Members
 ↓
Message
 ↓
Broadcast
```

---

## Giai đoạn 6 – GUI

Làm:

```text
Login
 ↓
Main
 ↓
Room
 ↓
Chat
```

---

## Giai đoạn 7 – Lịch sử

Khi vào phòng:

```text
JOIN_ROOM
     ↓
GET_HISTORY
     ↓
Database
     ↓
Server
     ↓
Client
```

---

## Giai đoạn 8 – Hoàn thiện

Thêm:

- Online/offline
- Avatar
- Emoji
- Gửi file
- Chat riêng

---

# 17. Phần demo mình đề xuất

Khi bảo vệ, đừng chỉ mở một Client.

Hãy mở:

```text
             SERVER
               │
       ┌───────┼───────┐
       │       │       │
     Vinh     Nam     An
   Client 1 Client 2 Client 3
```

### Demo 1

Vinh đăng nhập.

### Demo 2

Nam đăng nhập.

Server hiển thị:

```text
[INFO] Client connected: Vinh
[INFO] Client connected: Nam
```

### Demo 3

Vinh tạo phòng:

> Gaming

### Demo 4

Nam tham gia Gaming.

### Demo 5

Vinh gửi:

> Hello Nam!

Nam nhận ngay.

### Demo 6

An cũng vào Gaming.

Vinh gửi:

> Chào mọi người!

→ Nam và An cùng nhận.

### Demo 7

Tắt Client.

Server:

```text
[INFO] Nam disconnected
```

### Demo 8

Nam đăng nhập lại → vào Gaming → **lịch sử tin nhắn vẫn còn**.

Đây là một demo rất đẹp cho đề tài.

---

# 18. Những kiến thức mạng bạn sẽ trình bày được

Đề tài này có lợi thế là bạn có thể liên hệ trực tiếp với kiến thức môn học:

```text
Application Layer
       │
       │ JSON Protocol
       ▼
Transport Layer
       │
       │ TCP
       ▼
Network Layer
       │
       │ IP
       ▼
Client ↔ Server
```

Bạn có thể trình bày:

- TCP
- IP
- Port
- Socket
- ServerSocket
- Client-Server
- Multithreading
- Stream
- Serialization/JSON
- Connection management
- Broadcast
- Concurrent clients
- Database persistence

---

# 19. ⚠️ Một điều mình khuyên bạn thay đổi so với ý tưởng ban đầu

Ban đầu bạn nói muốn làm **“website như Discord”**.

Với đề tài IV.1 trong danh sách của giảng viên, mình nghĩ **đừng đặt mục tiêu là website ngay từ đầu**.

Hãy xác định:

> **Java Client–Server Chat Application**

trước.

Sau khi hoàn thành, nếu còn thời gian thì mới chuyển/đưa GUI lên Web.

Lý do là đề tài của bạn được đánh giá chủ yếu ở **lập trình mạng**, chứ không phải độ đẹp của website.

---

# 20. Cấu trúc cuối cùng mình khuyên dùng

Nếu là mình làm đồ án này, mình sẽ chốt:

```text
                    DISCORD MINI
                         │
             ┌───────────┴───────────┐
             │                       │
        CHAT SERVER              CHAT CLIENT
             │                       │
        Java TCP                  JavaFX
             │                       │
     ┌───────┼────────┐              │
     │       │        │              │
 Socket  Thread   Protocol           GUI
     │       │        │              │
     └───────┼────────┘              │
             │                       │
             └────────── TCP ────────┘
                         │
                      JDBC
                         │
                    PostgreSQL
```

**MVP đầu tiên chỉ cần 6 chức năng:**

> **Đăng nhập → Kết nối Server → Danh sách phòng → Vào phòng → Chat realtime → Lưu/đọc lịch sử.**

Sau khi 6 cái này chạy ổn, mới thêm chat riêng, file, emoji...

Nếu bạn muốn triển khai thật, **bước tiếp theo mình khuyên là thiết kế** **`Database + Protocol JSON + cấu trúc class Server/Client`** **trước**, rồi mới bắt đầu code. Mình có thể đi cùng bạn theo từng bước, từ **tạo 2 project NetBeans → tạo PostgreSQL → viết ChatServer TCP đa luồng → viết Client → kết nối 2 máy → làm GUI Discord Mini**.