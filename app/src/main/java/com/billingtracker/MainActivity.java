package com.billingtracker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.browser.customtabs.CustomTabsIntent;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

public class MainActivity extends Activity {

    // v3.27：改以「合法 https 來源」載入網頁，實際內容仍由打包進 assets 的檔案提供
    // （見 shouldInterceptRequest）。原因：離線 file:// 來源在發出 fetch 時 Origin 為 null，
    // Google OAuth token 端點與 Drive API 一律以 CORS 拒絕（Origin: null），導致「有跳回 App
    // 但雲端仍尚未連接」。改用註冊過的 https://tk101012000.github.io 來源後，CORS 通過，
    // token 交換 / 上傳 / 下載皆可運作；UI 資源仍離線由 assets 提供。
    private static final String APP_HOST = "tk101012000.github.io";
    // URL 路徑前綴 /expense-tracker/ 會被剝除，剩餘部分直接對應 assets 根目錄下的檔案
    // （assets 維持扁平：index.html、js/、css/、icons/…），無需重整目錄。
    private static final String URL_PREFIX = "/expense-tracker/";
    private static final String APP_URL = "https://tk101012000.github.io/expense-tracker/index.html";
    private static final int REQ_FILE_CHOOSER = 100;
    private static final int REQ_STORAGE = 101;   // #3 修復：API<29 匯出需執行期儲存權限

    private WebView webView;
    // 檔案選擇器的回傳 callback（Android 5+ 用 Uri[]）
    private ValueCallback<Uri[]> filePathCallback;

    // 網頁是否載入完成（cloud.js 的 BKOAuthBridge 才會存在）；
    // 若 OAuth 回傳來得比頁面載入更早，先暫存 pending，待 onPageFinished 再執行。
    private boolean pageReady = false;
    private String pendingOAuth = null;
    // v3.29：遷移階段若觸發 location.reload()，原本暫存的 pendingOAuth 會隨舊頁面遺失；
    // 改存此欄位，待 reload 完成後的 onPageFinished 再注入 BKOAuthBridge。
    private String deferredOAuth = null;

    // OAuth PKCE 狀態：連接時由網頁傳來，回傳時要能找回 verifier 完成 token 交換。
    // 存入 SharedPreferences 以在 App 被系統回收（WebView 重建）後仍可取回，
    // 這是雲端登入「找不到授權資訊」反覆失敗的根因。
    private SharedPreferences oauthPrefs;
    private String pendingVerifier = null;
    private String pendingProvider = null;

    // v3.27 一次性資料遷移：舊版以 file:// 為來源，localStorage 綁在 file:// origin；
    // 新版改用 https://tk101012000.github.io origin，兩者 localStorage 互不相通，
    // 若不遷移，升級後使用者的帳目會「消失」。故首次啟動時先載入舊 file:// 頁面
    // 讀出 localStorage，再載入 https 頁面把資料寫入新 origin（僅做一次，之後直載 https）。
    private static final String OLD_FILE_URL = "file:///android_asset/index.html";
    private static final String DUMP_JS =
        "(function(){var o={};for(var i=0;i<localStorage.length;i++){"
      + "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}return JSON.stringify(o);})()";
    private SharedPreferences appPrefs;
    private boolean migrating = false;
    private String migrationData = null;

    // 注入網頁的 JS：攔截 <a download> 的 blob:/data: 點擊，把內容以 base64 轉交原生下載。
    // 這解決了 WebView 不會自動處理 blob 下載的問題（否則「匯出 JSON/CSV」在 App 內會假動作）。
    private static final String INJECT_DL =
        "(()=>{if(window.__bkDlInjected)return;window.__bkDlInjected=true;"
      + "document.addEventListener('click',function(e){"
      + "var a=e.target;while(a&&a.tagName!=='A')a=a.parentNode;"
      + "if(!a||!a.hasAttribute('download'))return;"
      + "var href=a.href||'';"
      + "if(href.indexOf('blob:')!==0&&href.indexOf('data:')!==0)return;"
      + "e.preventDefault();e.stopPropagation();"
      + "var fn=a.getAttribute('download')||'download.bin';"
      + "fetch(href).then(r=>r.blob()).then(b=>{"
      + "var fr=new FileReader();"
      + "fr.onloadend=function(){var b64=fr.result.split(',')[1];"
      + "if(window.BKNATIVE&&window.BKNATIVE.downloadFile){window.BKNATIVE.downloadFile(b64,b.type||'application/octet-stream',fn);}"
      + "};fr.readAsDataURL(b);"
      + "}).catch(err=>console.error('bk-dl-failed',err));"
      + "},true);})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        oauthPrefs = getSharedPreferences("bk_oauth", MODE_PRIVATE);
        appPrefs = getSharedPreferences("bk_app", MODE_PRIVATE);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // 啟用 localStorage（資料持久化）
        ws.setDatabaseEnabled(true);
        // #10 修復：改為 MIXED_CONTENT_NEVER_ALLOW，防止 HTTPS 頁面載入 HTTP 資源導致 MITM
        //         （本 App 的 GitHub Pages 已是全 HTTPS，無需放行 HTTP 混合內容）
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccess(true);
        // v3.30：移除 WebView UA 中的 "wv" 標記，使 Google OAuth 不再判定為「嵌入 WebView」
        // 而報 disallowed_useragent。去掉後 UA 等同一般手機 Chrome，OAuth 流程與網頁版完全一致。
        String ua = ws.getUserAgentString();
        if (ua != null && ua.contains("wv")) {
            ua = ua.replace("; wv", "").replace(";wv", "").replace(" wv", "").replace("wv;", "");
            ws.setUserAgentString(ua);
        }

        // 頁面載入完成後注入下載攔截腳本，讓網頁的「匯出」能真的存檔
        webView.setWebViewClient(new WebViewClient() {
            // v3.27：攔截本網域（tk101012000.github.io/expense-tracker/…）的資源請求，
            // 由打包進 APK 的 assets 直接回應 → 頁面來源是合法 https 但內容完全離線。
            // 非本網域（Google/Dropbox/字型等）回傳 null 交由 WebView 走真實網路。
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    if (u == null) return null;
                    if (!APP_HOST.equalsIgnoreCase(u.getHost())) return null;   // 只攔本網域
                    String path = u.getPath();                                  // 例：/expense-tracker/js/app.js
                    if (path == null) return null;
                    String rel;
                    if (path.equals("/expense-tracker") || path.equals(URL_PREFIX)) {
                        rel = "index.html";                                    // 根路徑 → index.html
                    } else if (path.startsWith(URL_PREFIX)) {
                        rel = path.substring(URL_PREFIX.length());             // 剝除前綴：js/app.js
                    } else {
                        return null;                                           // 非本 App 路徑，走網路
                    }
                    if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html";
                    return serveAsset(rel);
                } catch (Exception e) {
                    return null; // 攔截失敗就退回網路，避免整頁白屏
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                boolean isFile = url != null && url.startsWith("file://");

                // ── v3.27 遷移階段一：舊 file:// 頁面載入完，讀出其 localStorage ──
                if (migrating && isFile) {
                    view.evaluateJavascript(DUMP_JS, value -> {
                        migrationData = value;                 // 已是 JSON 字串字面量（含跳脫）
                        runOnUiThread(() -> webView.loadUrl(APP_URL));  // 轉去 https origin
                    });
                    return;
                }

                pageReady = true;
                view.evaluateJavascript(INJECT_DL, null);

                // ── v3.27 遷移階段二：https 頁面載入完，把舊資料寫入新 origin（僅一次）──
                if (migrating && !isFile) {
                    migrating = false;
                    appPrefs.edit().putBoolean("https_migrated", true).apply();
                    // "{}" 為 4 字元，代表舊端無資料；有資料才注入並重載讓 app.js 重讀 DB。
                    if (migrationData != null && migrationData.length() > 4 && !"null".equals(migrationData)) {
                        // v3.29：若此刻有待處理的 OAuth 回傳，先移到 deferredOAuth，
                        // 否則隨即將發生的 location.reload() 會把 BKOAuthBridge 執行吃掉。
                        if (pendingOAuth != null) { deferredOAuth = pendingOAuth; pendingOAuth = null; }
                        String js = "(function(){try{var o=JSON.parse(" + migrationData + ");var n=0;"
                                  + "for(var k in o){if(localStorage.getItem(k)===null){localStorage.setItem(k,o[k]);n++;}}"
                                  + "if(n>0)location.reload();}catch(e){}})();";
                        view.evaluateJavascript(js, null);
                    }
                    migrationData = null;
                }

                // 頁面就緒：先處理「遷移 reload 後」的 deferred OAuth（確保在已遷移的頁面執行），
                // 再處理一般 pendingOAuth（OAuth 回傳早於頁面載入時暫存者）。
                if (deferredOAuth != null) {
                    String js = deferredOAuth; deferredOAuth = null;
                    view.evaluateJavascript(js, null);
                }
                if (pendingOAuth != null) {
                    String js = pendingOAuth;
                    pendingOAuth = null;
                    view.evaluateJavascript(js, null);
                }
            }
        });

        // 關鍵：實作 WebChromeClient.onShowFileChooser，讓 <input type="file"> 能跳出檔案選擇器
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams params) {
                // 若上一次還沒處理完，先取消它
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                try {
                    // createIntent() 會依 <input accept="..."> 自動設定過濾條件
                    Intent chooserIntent = params.createIntent();
                    startActivityForResult(chooserIntent, REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false; // 找不到可用的選擇器
                }
                return true;
            }
        });

        // 注入原生橋 BKNATIVE：網頁呼叫 BKNATIVE.openOAuth(url) 時，由 App 內 WebView 直接導航
        // 授權頁（v3.30 起不再用 Chrome Custom Tabs）；BKNATIVE.downloadFile 處理匯出下載。
        // 必須在 loadUrl 之前註冊，確保頁面首次執行 JS 時 window.BKNATIVE 已存在
        // （app.js 據此判斷是否跳過 Service Worker）。
        webView.addJavascriptInterface(new NativeBridge(), "BKNATIVE");

        // v3.27：首次啟動先做 file://→https 的 localStorage 遷移，之後一律直載 https。
        // 內容皆由 shouldInterceptRequest 從 assets 提供（離線）。
        boolean migrated = appPrefs.getBoolean("https_migrated", false);
        if (migrated) {
            webView.loadUrl(APP_URL);                 // https origin（內容走攔截器）
        } else {
            migrating = true;
            webView.loadUrl(OLD_FILE_URL);            // 先載舊 file:// 讀出既有資料
        }

        // 冷啟動若直接由自訂 scheme 進入（App 被完全殺掉後經由 billingtracker:// 調起）：
        // 先記錄 intent，待網頁載入完成（onPageFinished）後補執行 OAuth 回傳。
        handleOAuthCallback(getIntent());
    }

    /* ---------- OAuth 回傳（billingtracker://oauth/callback） ----------
       授權頁在系統瀏覽器 / Custom Tabs 完成後，網頁會以自訂 scheme 把 code/state 回傳，
       本 App 攔截後注入 WebView 執行 window.BKOAuthBridge 完成 token 交換。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthCallback(intent);
    }

    private void handleOAuthCallback(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        if (!"billingtracker".equals(uri.getScheme()) || !"/oauth/callback".equals(uri.getPath())) return;
        String code = uri.getQueryParameter("code");
        String stateKey = uri.getQueryParameter("state");
        String err = uri.getQueryParameter("error");
        if (code == null && err == null) return;

        // v3.28 診斷：確認回傳鏈確實到達 App（若使用者看不到此 Toast，表示 billingtracker:// 沒喚起 App）
        Log.d("BKCloud", "callback received code=" + (code != null ? code.substring(0, Math.min(6, code.length())) : "null") + " err=" + err);
        showToast("收到授權碼，連接中…");

        // 找回 PKCE verifier / provider：優先用記憶體，回退 SharedPreferences（App 被回收後仍可取回）
        String verifier = pendingVerifier;
        String provider = pendingProvider;
        if (oauthPrefs != null) {
            if (verifier == null) verifier = oauthPrefs.getString("verifier", "");
            if (provider == null) provider = oauthPrefs.getString("provider", "");
        }
        // 用完即清，避免舊狀態干擾下次連接
        pendingVerifier = null;
        pendingProvider = null;
        if (oauthPrefs != null) oauthPrefs.edit().clear().apply();

        String js = "window.BKOAuthBridge && window.BKOAuthBridge("
                + JSONObject.quote(code == null ? "" : code) + ","
                + JSONObject.quote(stateKey == null ? "" : stateKey) + ","
                + JSONObject.quote(err == null ? "" : err) + ","
                + JSONObject.quote(verifier == null ? "" : verifier) + ","
                + JSONObject.quote(provider == null ? "" : provider) + ");";
        // WebView 可能尚未就緒（cloud.js 尚未定義 BKOAuthBridge）：
        // 已載入則立即執行，否則暫存待 onPageFinished 補執行。
        if (pageReady) {
            Log.d("BKCloud", "inject BKOAuthBridge (pageReady=true)");
            webView.evaluateJavascript(js, null);
        } else {
            Log.d("BKCloud", "page not ready, defer BKOAuthBridge to onPageFinished");
            pendingOAuth = js;
        }
    }

    /** 供網頁呼叫的原生橋。 */
    private class NativeBridge {
        /** v3.28 診斷：網頁把雲端連線關鍵步驟經此輸出到 logcat（tag BKCloud） */
        @JavascriptInterface
        public void log(String msg) {
            Log.d("BKCloud", msg == null ? "" : msg);
        }

        /** v3.30：直接在 App 內 WebView 完成 OAuth（與網頁版同一頁流程），
         *  不再經由 Chrome Custom Tabs + 自訂 scheme 回傳——該跨程式鏈在多款 ROM 上靜默失敗，
         *  正是歷版「有授權頁、按完沒連接、也沒訊息」的根因。配合 onCreate 中移除 UA 的 "wv" 標記，
         *  Google 不再阻擋；授權完 Google 直接跳回本 App 的 https 來源（由 shouldInterceptRequest 服務），
         *  網頁層 handleRedirect() 在同一頁完成 token 交換。 */
        @JavascriptInterface
        public void openOAuth(String url, String stateKey, String verifier, String provider) {
            // 把 PKCE verifier 等 OAuth 狀態存起來（作為備援；頁內流程主要依賴 sessionStorage）
            pendingVerifier = verifier;
            pendingProvider = provider;
            if (oauthPrefs != null) {
                oauthPrefs.edit()
                    .putString("verifier", verifier == null ? "" : verifier)
                    .putString("provider", provider == null ? "" : provider)
                    .putString("state", stateKey == null ? "" : stateKey)
                    .apply();
            }
            Log.d("BKCloud", "openOAuth -> load in WebView (no Custom Tabs)");
            runOnUiThread(() -> webView.loadUrl(url));
        }

        // v3.23.1：網頁「匯出 JSON / CSV」時，把 blob 內容以 base64 傳來，
        // 由原生寫入系統「下載」資料夾（API29+ 用 MediaStore；舊版直接寫外部儲存）。
        @JavascriptInterface
        public void downloadFile(String base64, String mime, String filename) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IOException("無法在下載資料夾建立檔案");  // #2 修復：insert 回傳 null 時避免 NPE
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(data);
                    }
                } else {
                    // #3 修復：API 24–28 需 WRITE_EXTERNAL_STORAGE 執行期權限
                    if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        final String fn = filename;
                        runOnUiThread(() -> {
                            if (shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                showToast("需要儲存權限才能匯出檔案，請授予後重試");
                            }
                            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                        });
                        return;  // 等使用者授權後再次匯出即可
                    }
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File out = new File(dir, filename);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(data);
                    }
                }
                showToast("已儲存到下載資料夾：" + filename);
            } catch (Exception e) {
                showToast("匯出失敗：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    /* ---------- v3.27：從 assets 根目錄提供本網域資源 ----------
       relPath 例：index.html、js/app.js、css/styles.css（已剝除 /expense-tracker/ 前綴） */
    private WebResourceResponse serveAsset(String relPath) {
        try {
            InputStream is = getAssets().open(relPath);
            String mime = guessMime(relPath);
            boolean isText = mime.startsWith("text/")
                    || mime.equals("application/javascript")
                    || mime.equals("application/json")
                    || mime.equals("image/svg+xml");
            String encoding = isText ? "utf-8" : null;
            WebResourceResponse resp = new WebResourceResponse(mime, encoding, is);
            // 同源資源，附上寬鬆快取標頭即可；避免 no-store 造成重複讀 asset。
            resp.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-cache"));
            return resp;
        } catch (Exception e) {
            // 找不到對應 asset（例如某資源實際只在遠端）→ 回 null 走網路
            return null;
        }
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs"))   return "application/javascript";
        if (p.endsWith(".css"))  return "text/css";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".svg"))  return "image/svg+xml";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif"))  return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico"))  return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff"))  return "font/woff";
        if (p.endsWith(".ttf"))   return "font/ttf";
        return "application/octet-stream";
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    // 處理檔案選擇器的回傳結果
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            // 無論使用者選了檔案或取消，都必須呼叫 onReceiveValue，否則 input 會卡死
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // #3 修復：API<29 匯出儲存權限的回傳；授權結果不會自動重試匯出，提示使用者再次操作即可
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showToast("已授權儲存權限，請再次點擊匯出");
            } else {
                showToast("未授予儲存權限，無法匯出檔案");
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // 實體返回鍵：在 App 內往上一頁，而非直接關閉
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
