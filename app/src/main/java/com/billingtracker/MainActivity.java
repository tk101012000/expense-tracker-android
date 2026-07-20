package com.billingtracker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.browser.customtabs.CustomTabsIntent;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    // 離線模式：直接載入打包進 assets 的網頁，不依賴任何外部網址，安裝後即永久可用
    private static final String APP_URL = "file:///android_asset/index.html";
    private static final int REQ_FILE_CHOOSER = 100;

    private WebView webView;
    // 檔案選擇器的回傳 callback（Android 5+ 用 Uri[]）
    private ValueCallback<Uri[]> filePathCallback;

    // 網頁是否載入完成（cloud.js 的 BKOAuthBridge 才會存在）；
    // 若 OAuth 回傳來得比頁面載入更早，先暫存 pending，待 onPageFinished 再執行。
    private boolean pageReady = false;
    private String pendingOAuth = null;

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
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // 啟用 localStorage（資料持久化）
        ws.setDatabaseEnabled(true);
        // #10 修復：改為 MIXED_CONTENT_NEVER_ALLOW，防止 HTTPS 頁面載入 HTTP 資源導致 MITM
        //         （本 App 的 GitHub Pages 已是全 HTTPS，無需放行 HTTP 混合內容）
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setAllowFileAccess(true);

        // 頁面載入完成後注入下載攔截腳本，讓網頁的「匯出」能真的存檔
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                view.evaluateJavascript(INJECT_DL, null);
                // 若 OAuth 回傳在頁面載入前就到了，此時補執行
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

        // 離線模式：直接載入打包進 assets 的網頁（不附加 cache-buster，
        // assets 會隨 APK 一併更新，且本機資料 localStorage 不受影響）。
        webView.loadUrl(APP_URL);

        // 注入原生橋 BKNATIVE：網頁呼叫 BKNATIVE.openOAuth(url) 時，以系統瀏覽器 /
        // Chrome Custom Tabs 開啟 OAuth 授權頁，避免嵌入 WebView 的 UA 被 Google 擋下（disallowed_useragent）
        webView.addJavascriptInterface(new NativeBridge(), "BKNATIVE");

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
        String js = "window.BKOAuthBridge && window.BKOAuthBridge("
                + JSONObject.quote(code == null ? "" : code) + ","
                + JSONObject.quote(stateKey == null ? "" : stateKey) + ","
                + JSONObject.quote(err == null ? "" : err) + ");";
        // WebView 可能尚未就緒（cloud.js 尚未定義 BKOAuthBridge）：
        // 已載入則立即執行，否則暫存待 onPageFinished 補執行。
        if (pageReady) {
            webView.evaluateJavascript(js, null);
        } else {
            pendingOAuth = js;
        }
    }

    /** 供網頁呼叫的原生橋。 */
    private class NativeBridge {
        /** 以系統瀏覽器 / Chrome Custom Tabs 開啟 OAuth 授權頁 */
        @JavascriptInterface
        public void openOAuth(String url) {
            try {
                CustomTabsIntent tabs = new CustomTabsIntent.Builder().setShowTitle(true).build();
                tabs.launchUrl(MainActivity.this, Uri.parse(url));
            } catch (Exception e) {
                // Custom Tabs 不可用（如未安裝 Chrome）時，退回系統瀏覽器
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    MainActivity.this.startActivity(i);
                } catch (Exception ignored) {}
            }
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
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(data);
                    }
                } else {
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
