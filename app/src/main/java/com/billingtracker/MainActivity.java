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

public class MainActivity extends Activity {

    // 改成你自己的部署網址即可（保留結尾斜線）
    private static final String APP_URL = "https://tk101012000.github.io/expense-tracker/";
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

        // 每次啟動都在網址後加 cache-buster，強迫 WebView 抓取最新網頁，
        // 避免「要手動清快取才能更新」的問題。不影響 localStorage 資料。
        webView.loadUrl(APP_URL + "?nocache=" + System.currentTimeMillis());
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
