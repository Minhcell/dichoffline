# 📱 Dịch Thuật Pro — Build APK Android

## 🚀 Build bằng GitHub Actions (KHÔNG cần cài gì)

### Bước 1: Tạo repo GitHub
1. Vào https://github.com → **New repository**
2. Tên: `DichThuatPro`
3. Để Public → Create

### Bước 2: Sửa IP server
M�� file `app/build.gradle`, dòng 18, đổi IP:
```
buildConfigField "String", "SERVER_URL", "\"http://192.168.1.100:5000\""
```
→ Đổi `192.168.1.100` thành IP máy PC chạy server

### Bước 3: Push code lên GitHub
```bash
cd android-app
git init
git add .
git commit -m "Dịch Thuật Pro v3.0"
git branch -M main
git remote add origin https://github.com/TEN_BAN/DichThuatPro.git
git push -u origin main
```

### Bước 4: Tải APK
1. Vào GitHub repo → tab **Actions**
2. Nhấn workflow **Build APK** (tự chạy khi push)
3. Đợi ✅ xanh → nhấn vào
4. Kéo xuống **Artifacts** → tải **DichThuatPro-debug-apk**
5. Giải nén → cài file `.apk` lên điện thoại

### Tạo Release (tùy chọn)
```bash
git tag v3.0
git push origin v3.0
```
→ GitHub tự tạo Release có APK đính kèm → share link cho người khác tải

## ⚙️ Đổi IP server trong app (không cần build lại)
Khi app không kết nối được → hiện trang nhập IP → nhập IP mới → Lưu

## ⚠️ Lưu ý
- Cho phép "Cài từ nguồn không xác định" trên điện thoại
- Điện thoại phải cùng WiFi/LAN với PC chạy server
- Cho phép quyền Microphone khi app hỏi
