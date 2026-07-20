package com.billingtracker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import androidx.browser.customtabs.CustomTabsIntent;
import org.json.JSONObject;

public class MainActivity extends Activity {

    // 離線模式：直接載入打包進 assets 的網頁，不依賴任何外部網址，安裝後即永久可用
    private static final String APP_URL = "file:///android_asset/index.html";
    private static final int REQ_FILE_CHOOSER = 100;

    private WebView webView;
    // 檔案選擇器的回傳 callback（Android 5+ 用 Uri[]）
    private ValueCallback<Uri[]> filePathCallback;

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

        // 頁內連結都在 WebView 內開啟（含 OAuth 跳轉回 App）
        webView.setWebViewClient(new WebViewClient());

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

        // 冷啟動若直接由自訂 scheme 進入（極少見），等網頁載入後補處理 OAuth 回傳
        webView.postDelayed(() -> handleOAuthCallback(getIntent()), 2000);
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
        // WebView 可能尚未就緒，post 到主執行緒執行
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /** 供網頁呼叫：以系統瀏覽器 / Chrome Custom Tabs 開啟 OAuth 授權頁 */
    private class NativeBridge {
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
