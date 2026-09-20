# Zalo Poll Rank v1.3 — Auto Send

Ứng dụng Android thử nghiệm cho luồng:

1. Theo dõi một cuộc bình chọn Zalo theo đúng tiêu đề.
2. Khi thấy dòng `<Tên> tham gia cuộc bình chọn: <Tên poll>`, tự thêm STT 01, 02, 03...
3. Nếu bật **Tự nhắn**, dùng chính tài khoản Zalo đang đăng nhập trên điện thoại để gửi nội dung cập nhật vào cuộc trò chuyện.

## Cách tự gửi

Ứng dụng dùng 2 tầng, theo thứ tự an toàn:

### Tầng 1 — Direct Reply từ notification

Nếu notification Zalo có action Android `RemoteInput` (thường là nút **Trả lời**), app gửi nội dung trực tiếp qua action đó. Đây là đường tốt nhất vì không cần mở giao diện Zalo.

### Tầng 2 — Accessibility fallback

Nếu notification không hỗ trợ Direct Reply:

- app xếp nội dung vào hàng chờ;
- thử mở đúng cuộc trò chuyện từ `contentIntent` của notification;
- Accessibility kiểm tra tên nhóm đang hiển thị;
- tìm ô nhập tin nhắn;
- điền nội dung;
- chỉ click khi tìm thấy nút có nhãn chính xác `Gửi`/`Send`.

Nếu không xác nhận được đúng nhóm/nút Gửi, app giữ tin trong hàng chờ thay vì click mò.

## Nội dung gửi

Mặc định app gửi **toàn bộ danh sách hiện tại**, ví dụ:

```text
Đk chiều đá ae
01. Btc. Hải
02. Hải Lê
03. Phúc Nhân
```

Có thể bỏ chọn `Mỗi lần gửi: gửi cả danh sách hiện tại` để chỉ gửi dòng mới, ví dụ:

```text
03. Phúc Nhân
```

## Cài đặt lần đầu

- Bật **Quyền đọc thông báo** cho Zalo Poll Rank.
- Khuyến nghị bật thêm **Trợ năng** để có fallback.
- Nhập tên poll chính xác.
- Nhập tên nhóm Zalo nếu muốn fallback kiểm tra nhóm chắc chắn hơn.
- Bật `Tự nhắn cập nhật vào nhóm bằng nick Zalo của tôi`.
- Bấm `BẮT ĐẦU PHIÊN MỚI`.

## Hạn chế thực tế

- Zalo phải phát notification có chứa dòng tham gia poll thì chế độ nền mới bắt được sự kiện đó.
- Direct Reply chỉ hoạt động nếu notification Zalo của thiết bị/bản Zalo hiện tại có action trả lời bằng `RemoteInput`.
- Android có giới hạn khởi chạy app từ nền. Nếu Direct Reply không có và hệ thống không cho tự mở Zalo, tin vẫn được lưu chờ; mở đúng nhóm Zalo sẽ cho Accessibility thử gửi.
- App không mở khóa màn hình và không vượt PIN/vân tay/khuôn mặt.
- Giao diện Zalo có thể thay đổi, làm heuristic tìm ô chat/nút Gửi cần cập nhật.
- Zalo Developers hiện công khai API chủ yếu cho Official Account/ZBS/Social; bản này không dùng API chính thức để điều khiển tài khoản Zalo cá nhân mà dựa vào notification/Android UI automation.

## Lưu dữ liệu

v1.3 lưu danh sách và hàng chờ **cục bộ trên điện thoại**. Không dùng Notion/Google Sheet cho đường điều khiển chính vì cloud sẽ tăng độ trễ và yêu cầu thêm xác thực. Có thể thêm đồng bộ Google Sheet ở phiên bản sau mà không ảnh hưởng cơ chế bắt/gửi.

## Build APK bằng GitHub Actions

Workflow `.github/workflows/build-apk.yml` đã có sẵn. Push repo lên GitHub, workflow sẽ build `app-debug.apk` và xuất artifact `ZaloPollRank-install`.

