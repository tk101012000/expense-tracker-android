# 把「繳費記帳」打包成 Android APK

本目錄是一個標準的 **Android Studio 專案**，用 WebView 包裝你已部署的網頁 App
（https://tk101012000.github.io/expense-tracker/），編譯後即可得到可安裝的 `.apk`。

> 為什麼用 WebView 載入網址？這樣 APK 體積極小、功能與網頁版 100% 一致（含雲端備份），
> 且網站更新後 App 自動跟上；首次載入後靠 PWA 的 Service Worker 仍可離線使用。

---

## 方式一：用 Android Studio（最簡單，推薦）

1. 安裝 [Android Studio](https://developer.android.com/studio)（已內含 JDK + Android SDK + Gradle）
2. 開啟本目錄 `android-app/`（File → Open）
3. 等待 Gradle 同步完成（首次會下載約 200MB 套件，請保持網路）
4. 選單 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. 編譯完成後，底部會跳出通知，點 **locate** 開啟：
   ```
   android-app/app/build/outputs/apk/debug/app-debug.apk
   ```
6. 把 `app-debug.apk` 傳到手機 → 允許「未知來源」安裝 → 點擊安裝即可

### 想上架 Google Play？
用 **Build → Generate Signed Bundle / APK** 產生 `.aab`（需要自己的簽章金鑰），
再上傳 Play Console。Play 要求 `targetSdk` 較新版本，本專案已設 34。

---

## 方式二：命令列（已有 JDK17 + Android SDK + Gradle）

```bash
cd android-app
# 若無 gradlew wrapper jar，請用系統 Gradle 8.4 執行：
gradle assembleDebug
# 產物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 改成「完全離線、自帶程式碼」的 APK（不依賴網站）

若希望 App 完全自帶網頁、不連網也能開（但雲端備份會因 origin 為 file:// 而失效）：

1. 把 `expense-tracker/` 下的 `index.html`、`css/`、`js/`、`manifest.json`、`sw.js`、`icons/` 全部複製到
   `android-app/app/src/main/assets/`
2. 修改 `MainActivity.java` 的載入網址：
   ```java
   private static final String APP_URL = "file:///android_asset/index.html";
   ```
3. 重新 Build APK 即可

---

## APP 內「匯入 JSON」檔案按鈕

`MainActivity.java` 已實作 `WebChromeClient.onShowFileChooser` + `onActivityResult`，
因此 APP 內點「匯入 JSON」會直接跳出系統檔案選擇器，選好 `.json` 備份檔即可匯入
（不需要改用「貼上 JSON 文字」框，兩者皆可用）。

> 這是 **native 層**功能，程式碼改動後必須**重新 Build APK** 才會生效；
> 網頁端（GitHub Pages）的 `<input type="file">` 與讀取邏輯原本就存在，不需改動。
> 若用 `file://` 離線模式（上面「完全離線」段落），檔案選擇器同樣可用。

---

## 自訂

- **App 名稱**：`app/src/main/res/values/strings.xml` 的 `app_name`
- **載入的網址**：`MainActivity.java` 的 `APP_URL` 常數
- **圖示**：`app/src/main/res/drawable/ic_launcher.xml`（可換成 PNG mipmap）
- **版本號**：`app/build.gradle` 的 `versionCode` / `versionName`

---

## 注意事項

- `minSdk 24`（Android 7.0）可涵蓋絕大多數現役機種
- 雲端備份（Google Drive / Dropbox）需要網路，且依賴 `APP_URL` 為 https 網址；
  若改用 `file://` 離線模式，OAuth 重新導向網址會無效，建議改用網頁版做雲端備份
- 本專案不含任何後端程式碼，資料與網頁版共用同一套 localStorage 機制
