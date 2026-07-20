# 繳費記帳 · 家庭理財助手（Android APK 工程）

本專案是 [`tk101012000/expense-tracker-android`](https://github.com/tk101012000/expense-tracker-android) 的克隆，功能與原專案**完全一致**：

- **網頁版記帳 App**（純前端 PWA，已離線打包進 `app/src/main/assets/`）負責全部功能
- **Android WebView 殼**（`app/` 原生工程）把網頁包成可安裝的 `.apk`

> 與原作的差異：原作的 WebView 是線上載入 `https://tk101012000.github.io/expense-tracker/`；
> 本克隆改為**離線打包**——網頁檔直接打進 APK 的 `assets/`，不依賴任何外部網址，安裝後即永久可用
> （雲端備份 OAuth 在離線模式下不可用，其餘功能 100% 正常）。

---

## 功能一覽（與原專案相同）

| 功能 | 說明 |
|------|------|
| 記帳 | 收支紀錄新增 / 編輯 / 刪除（金額、日期、類別、備註、帳戶） |
| 繳費管理 | 每月 / 每季 / 每年週期項目，已繳 / 未繳標記，**勾選「標記為已繳」會自動產生一筆支出** |
| 到期提醒 | 到期前 7 天或逾期，於頂部鈴鐺與總覽高亮提示 |
| 分類統計 | 依月份產生「類別佔比圖」+「近 6 月趨勢圖」+ 類別排行（Canvas 自繪，無外部依賴） |
| 帳戶管理 | 現金 / 銀行 / 信用卡多帳戶，即時計算餘額與交易明細 |
| 搜尋篩選 | 關鍵字、日期區間、類別、帳戶、類型篩選 + 日期 / 金額排序 |
| 資料儲存 | localStorage 本地持久化 + 匯出 **CSV**（含 BOM，Excel 中文正常）/ **JSON** + JSON 匯入還原 |
| 雲端備份 | 可連接 Google Drive / Dropbox（需線上模式，見下方說明） |
| 輸入驗證 | 金額須 > 0、名稱必填，欄位即時錯誤提示 + toast；刪除二次確認；帳戶有交易則禁止刪除 |

技術：純靜態前端，無框架、無建置步驟；資料存在 `localStorage`（鍵 `billing_app_v1`）；
圖表用原生 Canvas 繪製；幣別依台灣習慣，支出紅、收入綠，符號 `¥`。

---

## 目錄結構

```
expense-tracker-android/
├── app/
│   ├── src/main/
│   │   ├── assets/          # ← 離線打包的網頁版（index.html / css / js / icons / manifest.json / sw.js）
│   │   ├── java/com/billingtracker/MainActivity.java   # WebView 載入 file:///android_asset/index.html
│   │   ├── res/             # 佈局、圖示、字串
│   │   └── build.gradle     # compileSdk 34 / minSdk 24 / targetSdk 34 / Java 17
│   └── build.gradle
├── gradle/                  # Gradle 8.13 wrapper
├── .github/workflows/build-apk.yml   # 推送後自動編出 debug APK
└── README.md
```

---

## 方式一：GitHub Actions 自動編 APK（推薦，免裝開發環境）

1. 把本專案推到你的 GitHub 倉庫（公開 / 私有皆可）
2. **Actions** 分頁 → 選 `Build APK` → `Run workflow`
   （或直接 `git push` 到 `main`/`master` 分支即自動觸發）
3. 跑完後在 **Actions → 該次執行 → Artifacts** 下載 `expense-tracker-apk`
4. 把 `app-debug.apk` 傳到手機 → 允許「未知來源」→ 安裝

> 雲端 runner 已內建 JDK 17 + Android SDK，會自動跑 `gradlew assembleDebug`，
> 無須在本機安裝 Android Studio。

---

## 方式二：Android Studio（本機編譯）

1. 安裝 [Android Studio](https://developer.android.com/studio)（含 JDK + SDK + Gradle）
2. File → Open 開啟本目錄，等待 Gradle 同步
3. Build → Build APK(s)，產物在 `app/build/outputs/apk/debug/app-debug.apk`

---

## 想改回「線上載入網址」模式（啟用雲端備份）

離線模式無法做 OAuth 雲端備份（OAuth 回跳需要 https 網址）。若需要雲端備份：

1. 把 `app/src/main/assets/` 裡的網頁部署到任意 https 網址（如 GitHub Pages）
2. 修改 `MainActivity.java` 的 `APP_URL` 為該網址（保留結尾斜線）
3. 重新 Build APK

---

## 自訂

- **App 名稱**：`app/src/main/res/values/strings.xml` 的 `app_name`
- **圖示**：`app/src/main/res/drawable/ic_launcher.xml`（或換 mipmap PNG）
- **版本號**：`app/build.gradle` 的 `versionCode` / `versionName`
- **類別 / 配色 / 幣別**：改 `assets/js/app.js` 頂部的 `EXPENSE_CATS` / `INCOME_CATS`、CSS 變數、`¥` 符號

> 任何網頁端改動後，需**重新 Build APK** 才會生效（assets 隨 APK 打包）。

---

## 隱私

所有記帳資料僅存在你裝置的 `localStorage`，未連雲端前不上傳任何資料；
雲端備份僅在你主動點擊「上傳」時經 OAuth 寫入你自己的 Google Drive / Dropbox 私人空間。
本專案無任何伺服器端程式碼。
