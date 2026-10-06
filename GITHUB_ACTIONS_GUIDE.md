# Hướng Dẫn Build APK Thủ Công Qua GitHub Actions

File cấu hình workflow đã được thiết lập sẵn tại: `.github/workflows/build-apk.yml`.

---

## 1. Cách kích hoạt build APK thủ công (Manual Build)

1. Đẩy code lên repository GitHub của bạn (Push to GitHub).
2. Mở repository trên trình duyệt, chuyển sang tab **Actions**.
3. Ở danh sách bên trái, chọn workflow **"Build Android APK"**.
4. Bấm vào nút **"Run workflow"** ở góc phải:
   - **Loại APK muốn build (`build_type`)**:
     - `debug` *(Khuyên dùng)*: Build bản Debug APK, cài trực tiếp vào mọi máy Android mà không cần chứng chỉ bảo mật.
     - `release`: Build bản Release APK tối ưu dung lượng.
     - `all`: Build đồng thời cả 2 bản Debug và Release.
   - **Chạy Unit Test trước khi đóng gói (`run_tests`)**: Tích chọn để kiểm tra tự động trước khi build.
5. Nhấn nút xanh **"Run workflow"** để bắt đầu.

---

## 2. Cách tải file APK sau khi build xong

1. Đợi tiến trình build chạy xong (khoảng 2 – 4 phút, hiện dấu tích xanh `✓`).
2. Nhấp vào lần chạy vừa hoàn thành.
3. Cuộn xuống mục **Artifacts** ở dưới cùng trang.
4. Nhấn vào mục **`ColorDetector-APKs-<run_number>`** để tải file ZIP chứa file `.apk` về máy tính hoặc điện thoại và cài đặt.

---

## 3. Các điểm đã được tối ưu để đảm bảo không bị lỗi build:
* **JDK 21 (Temurin):** Tương thích chuẩn 100% với Gradle 9.3 và AGP 9.1.
* **Tự động sinh `.env`:** Tránh lỗi của Secrets Gradle Plugin khi thiếu file môi trường trên runner.
* **Xử lý Keystore tự động:** Tự động giải mã `debug.keystore.base64` hoặc khởi tạo Keystore hợp lệ, đảm bảo không bị lỗi `Keystore file not found`.
* **Cơ chế Fallback cho Release:** Nếu bạn chưa cấu hình chứng chỉ riêng, workflow sẽ tự động dùng khóa an toàn để đóng gói mà không làm gãy quy trình build.
