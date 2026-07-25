# Dịch Offline — mọi ngôn ngữ ➜ Tiếng Việt

App Android dịch offline: nhận dạng giọng nói rồi dịch sang tiếng Việt, chạy được khi không có mạng.

## Mới ở v2

- **Hội thoại 2 người** với 4 chế độ: *tách giọng tự động* (mặc định), *bấm nút theo lượt*, *luân phiên tự động*, *một người nói*. Mỗi câu gắn nhãn NGƯỜI 1 / NGƯỜI 2 kèm vạch màu.
- **Lưu & xoá bản dịch**: nút **Lưu** đặt tên rồi cất vào *Bản dịch đã lưu* (menu ☰). Mỗi câu có nút ✕ để xoá riêng, nút **Xoá** để dọn hết màn hình.
- **Dịch nhanh hơn nhiều**: gói dịch được nạp sẵn (prewarm) ngay khi chọn ngôn ngữ, có bộ nhớ đệm câu, và bỏ qua kết quả cũ về trễ.
- **Nhạy hơn**: đọc micro theo khối 100ms, tự chốt câu khi im lặng (mặc định 800ms) thay vì đợi Vosk tự ngắt — đây là nguyên nhân chính khiến tiếng Hàn ở bản 1 phản hồi chậm.
- **Cài đặt** (menu ☰): chỉnh ngưỡng tách giọng và thời gian chốt câu.
- **Nhấn giữ một thẻ** để sửa lại người nói nếu tách nhầm.

## Tính năng

| Yêu cầu | Cách hoạt động |
|---|---|
| Dịch offline nhiều thứ tiếng | Google ML Kit Translate — mỗi ngôn ngữ tải gói ~30MB **một lần duy nhất**, sau đó dịch offline vĩnh viễn |
| Tự nhận diện tiếng / chọn sẵn ngôn ngữ | Ô chọn ở đầu màn hình. Chọn “Tự nhận diện ngôn ngữ” để app tự đoán |
| Thêm file ghi âm rồi bấm dịch | Nút **Chọn file ghi âm** — hỗ trợ mp3, m4a, aac, wav, amr, ogg, opus, flac, 3gp, mp4… (mọi định dạng Android giải mã được) |
| Hiện cả tiếng gốc và tiếng Việt | Mỗi thẻ kết quả có 2 phần: dòng trên là câu gốc, dòng dưới in đậm là bản dịch tiếng Việt |
| Nút Dịch = ghi âm, nói tới đâu dịch tới đó | Nút **Dịch (nói)** bật nhận dạng liên tục, kết quả tạm được dịch ngay theo thời gian thực |
| Biểu tượng loa đọc lại tiếng gốc | Nút loa bên phải câu gốc. Câu tiếng Việt không có nút loa (không cần đọc lại) |

## Chế độ hội thoại — chọn cái nào?

| Tình huống | Chế độ nên dùng |
|---|---|
| Hai người **cùng một thứ tiếng** (VD hai người Hàn) | **Tách giọng tự động** — cần tải thêm gói *tách giọng người nói* (13MB) |
| Một người Việt + một người nước ngoài | **Bấm nút theo lượt** — mỗi bên chọn ngôn ngữ riêng ở hai ô |
| Hai người nói luân phiên rất đều | **Luân phiên tự động** |
| Chỉ một người nói / dịch file một giọng | **Một người nói** |

Tách giọng dùng x-vector 128 chiều của Vosk, so cosine với tâm cụm từng người và cập nhật dần. Nếu app gộp hai người thành một thì giảm ngưỡng trong Cài đặt; nếu tách một người thành hai thì tăng lên.

## Hai bộ nhận dạng giọng nói

- **Vosk (mặc định, offline 100%)** — vào *Quản lý gói offline* tải gói cho ngôn ngữ cần dùng (32–100MB). Không cần mạng, không có tiếng “bíp”. **Bắt buộc dùng chế độ này khi dịch file ghi âm.**
- **Google** — tắt công tắc Vosk. Cần cài sẵn gói tiếng nói offline của Google trong *Cài đặt ▸ Hệ thống ▸ Ngôn ngữ ▸ Nhập bằng giọng nói*.

## Build APK bằng GitHub Actions

1. Tạo repo mới trên GitHub, upload toàn bộ thư mục này (kể cả `.github/`).
2. Vào tab **Actions** ▸ *Build APK* ▸ **Run workflow**.
3. Xong (~5–8 phút) tải file `DichOffline-debug-apk` ở mục Artifacts.

Workflow tự sinh `gradle-wrapper.jar` trên CI nên không cần commit file nhị phân.

Cấu hình: AGP 8.5.2 · Gradle 8.7 · JDK 17 (temurin) · Kotlin 1.9.24 · minSdk 24 · targetSdk 34

## Lần chạy đầu tiên

1. Mở app ▸ **Gói offline** ▸ tải gói ngôn ngữ cần nhận dạng (ví dụ Tiếng Anh, Tiếng Trung).
2. Quay lại, chọn ngôn ngữ nguồn (hoặc để “Tự nhận diện”).
3. Bấm **Dịch (nói)** và nói, hoặc **Chọn file ghi âm**.

> Lần đầu dịch một ngôn ngữ mới cần mạng vài giây để ML Kit tải gói dịch. Sau đó ngắt mạng vẫn chạy bình thường.

## Ghi chú kỹ thuật

- File ghi âm được giải mã bằng `MediaExtractor` + `MediaCodec`, tự hạ về PCM 16-bit mono 16 kHz (có bộ resample nội suy tuyến tính giữ trạng thái giữa các khối) rồi đưa thẳng vào Vosk.
- Nhận dạng micro bằng Vosk dùng `AudioRecord` với nguồn `VOICE_RECOGNITION`, đọc khối 200 ms.
- Nhánh Google có 3 lớp dự phòng lấy `lastPartial` khi `onResults` trả rỗng (lỗi hay gặp trên HyperOS/MIUI) và tự tắt tiếng bíp khi khởi động lại liên tục.
- Bản dịch của kết quả tạm được debounce 180 ms; mỗi lần dịch mang một số thứ tự nên kết quả cũ về trễ sẽ bị bỏ qua, không ghi đè kết quả mới.
- `TranslateEngine` chỉ gọi `downloadModelIfNeeded()` một lần cho mỗi ngôn ngữ rồi ghi nhớ, các lần sau gọi thẳng `translate()`.
- Bản dịch đã lưu nằm ở `filesDir/sessions/*.json`, có thể sao chép hoặc chia sẻ ra ngoài.

## Gói ngôn ngữ Vosk có sẵn trong app

Việt, Anh, Trung, Nhật, Hàn, Pháp, Đức, Tây Ban Nha, Bồ Đào Nha, Ý, Nga, Hà Lan, Thổ Nhĩ Kỳ, Hindi, Ba Lan, Ukraina, Séc, Catalan, Ba Tư, Gruzia, Telugu, Gujarati, Esperanto, Ả Rập, Philippines, Thụy Điển.

Các ngôn ngữ khác (Thái, Indonesia, Mã Lai, Hy Lạp, Do Thái…) vẫn **dịch** được, nhưng phần nhận dạng giọng nói phải dùng bộ máy Google.
