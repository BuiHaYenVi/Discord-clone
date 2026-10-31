# Hướng dẫn Driver PostgreSQL JDBC

Do quy định vệ sinh mã nguồn của giảng viên (**Nghiêm cấm commit các file nhị phân lớn như `.jar`, `.zip` vào Git để tránh bị trừ 20% điểm**), file `postgresql-42.7.3.jar` không được lưu trực tiếp trên Git repository.

## Cách 1: Tự động tải driver (Khuyên dùng)

### Trên Windows (PowerShell):
Chạy lệnh sau tại thư mục gốc dự án hoặc trong thư mục `Chatserver/lib/`:
```powershell
powershell -ExecutionPolicy Bypass -File .\Chatserver\lib\download_driver.ps1
```
Hoặc nhấp đúp chạy file `download_driver.bat`.

## Cách 2: Tải thủ công từ trang chủ PostgreSQL JDBC
1. Truy cập: https://jdbc.postgresql.org/download/
2. Tải phiên bản: `postgresql-42.7.3.jar`
3. Đặt file vừa tải vào đúng thư mục này: `Chatserver/lib/postgresql-42.7.3.jar`
4. Mở NetBeans và nhấn **Clean and Build** dự án Chatserver.
