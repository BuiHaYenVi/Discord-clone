## 1. Thông Tin Chung
- **MSSV & Họ tên người tạo**: <!-- Ví dụ: 2380602519 - Bùi Hà Yến Vi -->
- **Giải quyết Issue số**: <!-- Ví dụ: Close #18 -->
- **Milestone liên quan**: <!-- Ví dụ: Tuần 7: Kiểm thử toàn diện & Chuẩn hóa tài liệu -->
- **Nhánh tính năng**: <!-- Cú pháp: feature/<MSSV>-issue-<Số_Issue>-<Tên_ngắn_gọn> -->

---

## 2. Mô Tả Chi Tiết Công Việc (Đã làm gì?)
- [ ] <!-- Mô tả tóm tắt tính năng, cải tiến hoặc lỗi được sửa -->
- [ ] <!-- Các file chính được thêm mới hoặc chỉnh sửa -->

---

## 3. Đánh Giá Ảnh Hưởng (Impact Analysis)
- **Có ảnh hưởng đến module khác không?**: [ ] Có / [ ] Không
- **Chi tiết ảnh hưởng (nếu có)**:
  - Client Swing: 
  - ChatServer / Protocol: 
  - PostgreSQL Database: 

---

## 4. Bằng Chứng Kiểm Thử & Chạy Thực Tế (Bắt buộc)
> *Yêu cầu đính kèm ảnh chụp màn hình hoặc log chứng minh code đã build và chạy thành công trên máy cục bộ.*

<!-- Dán ảnh chụp màn hình chạy test / giao diện ứng dụng tại đây -->

---

## 5. Danh Sách Kiểm Tra Vệ Sinh Mã Nguồn (Pre-merge Checklist)
- [ ] Tuân thủ Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`).
- [ ] Không chứa file build (`build/`, `dist/`), cấu hình cá nhân (`.idea/`, `.vscode/`, `nbproject/private/`), hoặc file nhị phân lớn (`.jar`, `.zip`).
- [ ] Không push trực tiếp lên `main`; tạo PR từ nhánh `feature/...` hợp lệ.
- [ ] Đã chỉ định ít nhất 1 thành viên khác làm Peer Reviewer (Nghiêm cấm tự Approve).
