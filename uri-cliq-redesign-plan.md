# Kế hoạch redesign Uri theo visual style của Cliq

Ngày lập: 18/10/2026. Trạng thái: đã hoàn thành triển khai UI & kiểm chứng (M1–M6).

Mục tiêu: đưa font, typography, padding, màu sắc và cách trình bày component của Cliq trong `FE-ZOHO` sang project này, đồng thời giữ layout và toàn bộ hành vi hiện tại. Ưu tiên lấy lại markup/CSS/component trình bày có sẵn, tránh thiết kế lại từ đầu hoặc kéo cả ứng dụng DevZoho sang.

## 1. Phạm vi đã chốt với người dùng

- **Giữ layout hiện tại**: dock trái, cột sidebar, search pill, card chat, member drawer và các vị trí điều hướng.
- **Đổi font/padding/style theo Cliq**, không chuyển sang layout hay hệ navigation của Cliq.
- **Tên sản phẩm là Uri**. Giữ tên người dùng, guild, channel và dữ liệu nghiệp vụ.
- Áp dụng cho frontend React: chat, DM/bạn bè, sidebar/member, auth, modal/settings, voice/video và network monitor.
- Giữ API, AppStore, WebSocket, TCP/UDP, WebRTC, quyền server và các chức năng vừa merge từ issue #9.
- Không redesign Java Swing, đổi schema DB, thêm Notes/Calendar/CRM/Taz Bot hay tính năng chat mới trong đợt này.
- Đợt làm việc hiện tại chỉ tạo kế hoạch này; chưa copy component hoặc sửa source UI.

## 2. Bối cảnh đã kiểm tra

**Nguồn tham chiếu:** `C:\Users\super\source\repos\DevZoho\FE-ZOHO`, revision quan sát `5b393619`. Các file Cliq/CSS được kiểm tra không có thay đổi local tại thời điểm khảo sát.

**Project đích:** `C:\Users\super\source\repos\Uri\DA_LapTrinhMangMayTinh`, frontend trong `frontend/`.

| Phát hiện từ code | Ý nghĩa cho kế hoạch |
| --- | --- |
| Cliq dùng `Quicksand` trong `src/index.css`, `ChatPanel` và `ChatInputArea`. | Lấy Quicksand làm chuẩn, không dựa vào tên class `zoho-puvi` để chọn font. |
| Project đích **đã** có Quicksand trong `frontend/index.html` và `--font-sans` trong `src/design/tokens.css`. | Không cần thêm font mới. Cần thống nhất size/weight/line-height, inheritance và loại bỏ khai báo font thừa. Giữ font mono cho protocol/code. |
| `DESIGN.md` local mới ghi Kingwork/Inter, trong khi runtime vẫn là Uri/Oceanic cũ với Quicksand và màu sage. | Khi triển khai, reconcile tài liệu thành **Uri + Quicksand + visual Cliq**; không ghi đè tài liệu hiện tại trong bước lập plan. |
| Layout thực dùng dock `60px`, sidebar `330px`/`350px`, max-width `1560px`, search pill `48px`, gap ngoài `16px`. | Giữ các kích thước cấu trúc này. Không chép width `268px` của Cliq hoặc áp nhầm token sidebar `240px` hiện đã lệch implementation. |
| Theme đích dùng `[data-theme='dark']`; source Cliq dùng Tailwind `dark:` với class `.dark`. | Cần adapter dark variant trước khi port class; tránh component lấy từ source chỉ đúng light mode. |
| Đích dùng React 19/Tailwind 4/Lucide. `framer-motion ^14.3.0` đã có trong thay đổi package local; nguồn đang khai báo `^13.1.1`. | Không thay package/lockfile hoặc downgrade theo source. Chỉ dùng motion nếu component được giữ lại thực sự cần và API đã kiểm tra. |
| Model message của Cliq có string ID, `isPinned`, `parentPreview`, attachment đơn; Uri có numeric ID, `pinned`, `replyTo`, `attachments[]`. | Port presenter theo model Uri; không đổi model store/API chỉ để khớp component nguồn. |

### Giới hạn khảo sát

- Đã đối chiếu code, CSS, import và props; chưa chạy Cliq ở trình duyệt để đo computed style hoặc xác nhận font thực tải thành công.
- GitNexus được nhắc trong `FE-ZOHO/AGENTS.md`, nhưng checkout không có `.gitnexus/run.cjs`, command hoặc MCP GitNexus khả dụng. Dependency map dưới đây là phân tích import tĩnh; chưa coi việc copy nguyên component lớn là đã qua graph impact analysis.
- Repo đích đang có thay đổi local ở `.gitignore`, `DESIGN.md`, `frontend/package.json` và `frontend/package-lock.json`. Đây là baseline cần giữ; không stash/reset hoặc đưa chúng vào commit một cách tự động.

## 3. Visual contract: đổi skin, giữ cấu trúc

Các giá trị dưới đây là mục tiêu triển khai ban đầu. Chốt lại bằng screenshot và computed style ở milestone 1; không tự diễn giải thành thay layout.

| Nhóm | Chuẩn cho Uri | Dấu vết tham chiếu |
| --- | --- | --- |
| Font UI | Quicksand 400/500/600/700; fallback system sans | `FE-ZOHO/src/index.css`, `ChatInputArea.tsx` |
| Font kỹ thuật | Giữ JetBrains Mono cho code/frame/log/RTT | `frontend/src/design/tokens.css` hiện tại |
| Body chat | 13–14px, line-height khoảng 1.5; đủ rõ dấu tiếng Việt | Cliq dùng message primitive `text-xs leading-relaxed`; không áp size nhỏ của badge lên cả nội dung chat |
| Label/sidebar | 12–13px; weight 500/600/700 theo hierarchy | `CliqSidebar.tsx`, `ChatAvatar.tsx` |
| Heading | 14–16px cho section/header; metadata 11–12px | Giảm việc dùng đồng loạt title lớn/font-black ở chrome hiện tại |
| Spacing | Thang 4/6/8/10/12/16/20/24px; mỗi vùng dùng một nhịp rõ ràng | `p-3`, `gap-1.5`, `px-5`, `py-2.5` trong Cliq |
| Timeline | Padding nội bộ ngang 20px, trên 16px, dưới 8px ở desktop; mobile giảm theo breakpoint | `ChatPanel.tsx`: `px-5 pt-4 pb-2` |
| Composer | Vỏ padding 12px, gap 6–10px; giữ footprint và vị trí hiện tại | `ChatInputArea.tsx`: `p-3 ... gap-1.5` |
| Bubble | Padding 16px/10px; radius nhóm tin tham chiếu 20px và góc nối 5px; giữ alignment hiện có | `ui/message.tsx`, `MessageBubble.tsx` |
| Màu nhấn | Blue `#2563eb`, hover `#1d4ed8`, selected `#eff6ff`, border `#bfdbfe` | Các component Cliq và phần palette trong `DESIGN.md` local |
| Light surface | Canvas slate `#f8fafc`, card trắng/trắng 95%, border slate nhẹ | Thay lớp màu sage/cream, giữ dock/card/search pill ở đúng vị trí |
| Dark surface | Canvas `#090d16`, card `#0f172a`, border slate; chữ slate sáng | `ChatPanel.tsx`, `CliqSidebar.tsx` |
| Radius/shadow | Card 24px, control 8–12px, pill giữ hình pill; shadow nhẹ hơn hiện tại | `rounded-3xl`, `rounded-xl`, `shadow-xs` của Cliq |
| Interaction | Hover/active/focus/disabled rõ; tooltip và focus keyboard đầy đủ; giảm motion khi được yêu cầu | Giữ mức animation hiện tại, không thêm layout animation/hiệu ứng trang trí mới |

**Ranh giới layout:** giữ thứ tự cột, width dock/sidebar, max-width, search pill độc lập, các vị trí toolbar/composer/member drawer và cơ chế responsive. Padding có thể đổi bên trong từng vùng; không tái cấu trúc `WorkspaceContent` thành `ZohoCliqPage` hay thêm nhiều chat panel.

## 4. Bản đồ code có thể tái sử dụng

Đường dẫn nguồn bên dưới tương đối với `FE-ZOHO/src/`; đường dẫn đích tương đối với `frontend/src/`. Các file đích mới được ghi rõ. Mỗi phần được port cần có ghi chú nguồn/revision và liệt kê các chỗ đã bỏ dependency.

| Nguồn | Cách lấy sang | Điểm ghép ở Uri / điều kiện |
| --- | --- | --- |
| `index.css` | Lấy font stack, màu và giá trị surface cần dùng; không copy cả global stylesheet | `design/tokens.css`, `index.css`, font loader trong `frontend/index.html` |
| `components/ui/message.tsx` | Lấy markup/classes của `MessageContent`, action toolbar và nhóm message | Tạo **mới** `shared/ui/message-primitives.tsx`; dùng class map nhỏ, không bắt buộc thêm `class-variance-authority`/`clsx`/`tailwind-merge` chỉ vì source có chúng |
| `components/cliq/ChatAvatar.tsx` | Port gần nguyên phần presenter: ảnh/fallback/initial/status ring | Tạo **mới** `shared/ui/Avatar.tsx`; thay `any` bằng type Uri, label qua i18n hiện tại; map status enum tường minh, không mặc định mọi status lạ thành online |
| `components/cliq/MessageDateDivider.tsx` | Lấy divider markup/style và trạng thái last-read nếu dữ liệu đích đã có | Tạo **mới** `features/chat/MessageDateDivider.tsx`; label/callback qua props. Không hiện nút nhảy ngày nếu Uri chưa có hành vi đó |
| `components/cliq/PinnedMessageBar.tsx` | Giữ phần hiển thị và cách chuyển giữa tin ghim; đổi type/callback | Tách presenter trong `features/chat/`; nối `pinnedMessages`, jump/unpin đang có. Không copy logic quyền riêng của Zoho |
| `components/cliq/MessageBubble.tsx` | Lấy radius theo nhóm, typography, padding, reaction chip và hover actions | Tách presenter **mới** `features/chat/MessageItem.tsx` từ `ChatStage`; giữ parser text an toàn và callback hiện tại. Không copy nguyên component 1.062 dòng |
| `components/cliq/ChatInputArea.tsx` | Lấy vỏ composer, reply/edit preview, vị trí toolbar và state style | Restyle `features/chat/MessageComposer.tsx`; giữ textarea, IME, upload, mention và send/edit handlers hiện có. Không kéo rich editor/format không được hỗ trợ sang |
| `components/cliq/CliqSidebar.tsx` | Lấy style row, badge unread, section label, search, hover/selected | Restyle `features/guild/ChannelSidebar.tsx`, `GuildRail.tsx`, `MemberSidebar.tsx`, không thay navigation hoặc width cột |
| `components/cliq/FileAttachmentCard.tsx`, `FileTypeIcons.tsx` | Lấy markup card và SVG icon thuần cần dùng | Tách card trình bày khi cần; giữ `Attachment[]`, URL/download/auth flow của Uri. Không copy `api`, `fileDownloader` của Zoho |
| `components/cliq/VoiceMessagePlayer.tsx` | Chỉ lấy style nút, label, progress/duration phù hợp | Restyle `features/chat/VoiceWaveformPlayer.tsx`; không thay cơ chế media hoặc thêm chức năng ghi âm mới |
| `components/cliq/ChatPanel.tsx` | Tham khảo frame/header/body/composer spacing | Áp vào `ChatStage` và card hiện tại; không port controller 1.120 dòng, API, meeting state hoặc panel management |

### Những phần giữ ở project đích

- `shared/state/appStore.tsx`, `shared/api/apiClient.ts`, `shared/ws/wsClient.ts` tiếp tục sở hữu dữ liệu và hành vi.
- UI mới nhận props/callback hoặc dùng facade store hiện tại; không import ngược API/store/i18n của DevZoho.
- Dùng dictionary vi/en hiện có ở `shared/i18n/translations.ts`; không kéo cả `react-i18next` và các namespace SalesIQ/Zoho sang chỉ để đổi giao diện.
- Giữ renderer message hiện tại. Source có `rehypeRaw` đi kèm sanitizer; không copy raw HTML rendering mà bỏ sanitizer. Mục tiêu đợt này không cần đổi renderer.
- Không copy Redux, Socket.IO, tổ chức/tenant, router, Meeting module hay Ant Design Tooltip. Giữ Lucide và cơ chế tooltip/popover của Uri.
- Nếu một component nguồn cần nhiều adapter hơn phần markup tái sử dụng được, chỉ port phần view/class cần thiết; không đổi backend để phục vụ visual reuse.

## 5. Thứ tự triển khai và commit milestone

- [x] **M1 — Chốt baseline, Uri brand và token contract.** Chụp desktop/mobile light/dark trước khi sửa; đo font/padding ở Cliq và Uri; lưu mapping token. Thống nhất `DESIGN.md` thành Uri/Quicksand. Giữ các thay đổi package local. Đồng bộ token kích thước với layout thực, không dùng các width cũ 72/240px. Chuẩn hóa `dark:` theo `[data-theme='dark']`. **Đạt khi:** font thực tải đúng, theme đổi đầy đủ, hình học layout không thay. Commit: `style(uri): align Cliq typography and tokens`.
- [x] **M2 — Port các presenter nhỏ.** Lấy message primitives, avatar, divider, icon/button/badge cần dùng theo bảng trên. Tách dependency i18n/type, giữ numeric ID Uri và callback hiện tại. Chỉ tạo component thực sự được một màn hình sử dụng. **Đạt khi:** presenter không import DevZoho API/store, render đúng light/dark và không phát request mới. Commit: `refactor(ui): port Cliq presentation components`.
- [x] **M3 — Restyle dock/sidebar/member/header.** Áp font scale, padding, selected/hover/unread state, border và shadow mới vào `GuildRail`, `ChannelSidebar`, `MemberSidebar` và header trong `App.tsx`/`ChatStage`. Giữ vị trí search pill và modal quản lý kênh vừa làm. **Đạt khi:** so sánh before/after cùng viewport thấy nguyên bố cục; tên dài/unread/badge không tràn. Commit: `style(ui): apply Cliq chrome to Uri workspace`.
- [x] **M4 — Restyle chat/DM/composer.** Ghép `MessageItem`, avatar/date/pin presenter; áp bubble/reaction/reply/attachment styles. Đồng bộ `FriendsAndDmStage` và `MessageComposer`, không đổi send/edit/delete/upload/mention/typing handlers. **Đạt khi:** dòng dài, code, link, tiếng Việt, lỗi upload, reply/edit, loading và tin đã xóa đều hiển thị đúng; scroll và draft không bị mất. Commit: `style(chat): align Uri message and composer styling`.
- [x] **M5 — Hoàn tất Uri branding và các surface còn lại.** Đổi tên hiển thị/title/meta/favicon/aria/auth/dictionary thành Uri; restyle settings, command palette, toast, modal/confirmation, auth, voice/video và network monitor. Giữ các màu TCP/UDP/WS/HTTP có nghĩa và font mono của frame. Không đổi key storage/token, protocol label, API URL, tên repo hay dữ liệu chỉ vì đổi brand. **Đạt khi:** không còn tên sản phẩm Oceanic/Discord/Kingwork ở chrome thuộc phạm vi, mọi dialog cùng hệ button/input/focus. Commit: `style(uri): unify branding and secondary surfaces`.
- [x] **M6 — Visual QA và regression.** Chạy build/lint, kiểm tra các warning baseline trước khi bắt đầu để không gán nhầm lỗi cũ cho redesign. Test lại Java để phát hiện sửa lan ngoài FE; kiểm tra Web/Web và Web/Java khi backend thật sẵn sàng. Chụp after ở cùng viewport/bộ dữ liệu và đối chiếu từng surface. **Đạt khi:** toàn bộ tiêu chí ở phần 6 đạt; ghi rõ phần chưa kiểm chứng và commit từng milestone sau khi check. Commit cuối nếu có sửa QA: `test(ui): verify Uri Cliq visual consistency`.

M1 → M2 là nền cho các bước sau. M3 và phần presenter độc lập của M4 có thể làm song song khi đã phân quyền file rõ; file `tokens.css`, `index.css`, `App.tsx`, `ChatStage` có một người điều phối để tránh ghi đè.

## 6. Acceptance criteria và kiểm chứng

### Visual và bố cục

- Uri dùng thống nhất Quicksand trong light/dark; mono chỉ xuất hiện ở dữ liệu kỹ thuật/code. Kiểm tra computed `font-family`, weight và font loading, không chỉ nhìn tên biến CSS.
- Tên sản phẩm Uri xuất hiện đúng ở tab trình duyệt, auth, logo/brand label và accessibility labels.
- Giữ dock + sidebar/search pill + card chat/member drawer như trước. Không có suite sidebar của Zoho hay panel chat mới.
- Kiểm tra tại 390px, 768px, 1024px, 1440px và 1920px; không horizontal overflow, textarea/composer không che tin, drawer/modal không nằm ngoài màn hình.
- Padding/font-size/radius/color lấy từ token hoặc primitive đã thống nhất; tránh hai bản style riêng cho DM và channel có cùng loại control.
- Light/dark, focus-visible, hover, selected, disabled, empty/loading/error đều có cùng ngôn ngữ thiết kế; keyboard và reduced-motion vẫn dùng được.

### Hành vi phải giữ

- Gửi tin, sửa/xóa, reply, reaction, pin, attachment và @mention vẫn gọi đúng các handler/API hiện tại; không gửi trùng khi Enter/click nhanh.
- Nhập tiếng Việt/IME không bị gửi nhầm; draft, vị trí cuộn và focus không mất khi state realtime cập nhật hoặc đóng/mở sidebar.
- Đổi tên/mô tả/xóa kênh, xác nhận xóa, quyền OWNER/ADMIN và cập nhật role cache vẫn hoạt động như bản đã merge.
- DM, unread, search, reconnect/presence, voice/video và monitor giữ nguyên dữ liệu/luồng xử lý; không thêm nút không có backend.
- Asset/attachment vẫn dùng cơ chế tải xác thực của Uri, không gọi endpoint của DevZoho hoặc đưa token vào URL để phục vụ component được copy.

### Lệnh kiểm tra khi triển khai

```powershell
rtk proxy npm --prefix frontend run build
rtk proxy npm --prefix frontend run lint
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-merge.ps1
```

Chỉ chạy lại các kiểm tra sau khi có code thay đổi; không cần cài thêm framework test hay chạy toàn bộ FE-ZOHO để tạo kế hoạch này. Bộ kiểm thử thực hiện redesign phải phân biệt demo visual QA với Web/Java E2E có database thật.

## 7. Quyết định còn lại

Không có câu hỏi đang chặn kế hoạch: layout giữ nguyên và tên Uri đã được người dùng xác nhận. Cỡ chữ/padding chi tiết sẽ được chốt bằng đối chiếu screenshot/computed style ở M1; nếu kết quả buộc phải đổi cấu trúc cột hoặc thêm tính năng, tách đề xuất ra khỏi phạm vi đã duyệt.
