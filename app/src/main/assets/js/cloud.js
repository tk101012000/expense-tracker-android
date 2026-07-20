/* =========================================================
   雲端備份模組  ·  客戶端 OAuth2 PKCE（無後端、無密鑰暴露）
   支援 Google Drive（appDataFolder 私人空間）與 Dropbox
   v3.14 審計修復版（#3 憑證處理 / #12 併發鎖）→ v3.17 修復 disallowed_useragent（OAuth 改由系統瀏覽器 / Chrome Custom Tabs 開啟，回傳經 billingtracker:// scheme）
   ========================================================= */
(function () {
  'use strict';

  const STORE = 'billkeeper_cloud';
  // 雲端 OAuth 回傳網址必須是「託管的合法網址」（已在 Google Cloud 註冊）。
  // 自 v3.27 起 App 內 WebView 改以 https://tk101012000.github.io/expense-tracker/index.html
  // 為來源載入（由原生 shouldInterceptRequest 提供本地 assets），因此 location.origin+
  // location.pathname 會變成 .../expense-tracker/index.html（含 index.html），與 Google
  // 註冊的 .../expense-tracker/（結尾斜線）不符 → redirect_uri_mismatch。
  // 故 redirect_uri 一律固定用託管根網址（含結尾斜線），不隨載入路徑變動，
  // 由該頁 inline snippet 轉 billingtracker:// 回傳 App。
  const REDIRECT = 'https://tk101012000.github.io/expense-tracker/';

  // v3.28 診斷輔助：把雲端連線關鍵步驟經 BKNATIVE.log 輸出到 logcat，
  // 並在錯誤時把訊息寫入雲端狀態區（確保使用者一定看得到，不依賴 alert/Toast 是否彈出）。
  function nativeLog(m) {
    try { if (window.BKNATIVE && typeof window.BKNATIVE.log === 'function') window.BKNATIVE.log(String(m)); } catch (e) {}
  }
  function setStatus(t) {
    try { const el = document.getElementById('cloudStatus'); if (el) el.textContent = t; } catch (e) {}
  }

  const PROVIDERS = {
    drive: {
      name: 'Google Drive',
      authUrl: 'https://accounts.google.com/o/oauth2/v2/auth',
      tokenUrl: 'https://oauth2.googleapis.com/token',
      scope: 'https://www.googleapis.com/auth/drive.appdata',
      extraAuth: 'access_type=offline&include_granted_scopes=true&prompt=consent',
    },
    dropbox: {
      name: 'Dropbox',
      authUrl: 'https://www.dropbox.com/oauth2/authorize',
      tokenUrl: 'https://api.dropboxapi.com/oauth2/token',
      scope: '',
      extraAuth: 'token_access_type=offline',
    },
  };

  /* ---------- 工具 ---------- */
  const b64url = bytes => {
    let s = '';
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  };
  const randomStr = n => {
    const a = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
    const arr = new Uint8Array(n);
    crypto.getRandomValues(arr);
    let r = '';
    for (const v of arr) r += a[v % a.length];
    return r;
  };
  async function pkceChallenge(verifier) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    return b64url(new Uint8Array(digest));
  }
  const toast = (m) => (window.toast ? window.toast(m) : alert(m));

  /* ---------- 狀態 ---------- */
  let state = loadState();
  function loadState() {
    try { return JSON.parse(localStorage.getItem(STORE)) || {}; } catch { return {}; }
  }
  function saveState() { localStorage.setItem(STORE, JSON.stringify(state)); }

  function tokenValid() {
    return state.accessToken && state.expiresAt && Date.now() < state.expiresAt - 60000;
  }

  /* ---------- OAuth 流程 ---------- */
  async function connect() {
    const provider = $('#cloudProvider').value;
    const clientId = $('#cloudClientId').value.trim();
    // #3 修復：clientSecret 不再長期存入 localStorage；
    //         只在本次 session 中暫存（用 sessionStorage），僅用於一次 token exchange
    const clientSecret = $('#cloudClientSecret').value.trim();
    if (!clientId) { toast('請先填入 ' + PROVIDERS[provider].name + ' 的 Client ID / App Key'); return; }

    state.provider = provider; state.clientId = clientId;
    // #3 修復：secret 存 sessionStorage（關閉 tab 即消失），不寫入 localStorage
    if (clientSecret) sessionStorage.setItem('bk_cs', clientSecret);
    else sessionStorage.removeItem('bk_cs');
    saveState();

    const verifier = randomStr(64);
    const challenge = await pkceChallenge(verifier);
    const stateKey = randomStr(24);
    sessionStorage.setItem('bk_oauth_' + stateKey, JSON.stringify({ provider, verifier }));

    const p = PROVIDERS[provider];
    const params = new URLSearchParams({
      client_id: clientId,
      redirect_uri: REDIRECT,
      response_type: 'code',
      code_challenge: challenge,
      code_challenge_method: 'S256',
      state: stateKey,
    });
    if (p.scope) params.set('scope', p.scope);
    if (p.extraAuth) p.extraAuth.split('&').forEach(kv => { const [k, v] = kv.split('='); params.set(k, v); });

    const target = p.authUrl + '?' + params.toString();
    // 修復 disallowed_useragent：嵌入 WebView 的 UA 含 "; wv;"，Google 會阻擋 OAuth。
    // 改由 Android 端 BKNATIVE.openOAuth 以「系統瀏覽器 / Chrome Custom Tabs」開啟授權頁；
    // 非 App 環境（桌面瀏覽器）才退回原本的 location.href。
    if (window.BKNATIVE && typeof window.BKNATIVE.openOAuth === 'function') {
      // 把 PKCE verifier / provider / stateKey 一併交給原生層保存（SharedPreferences），
      // 回傳時即使 WebView 被重建也能找回 verifier 完成 token 交換（雲端登入失敗的主因）。
      nativeLog('openOAuth called provider=' + provider);
      window.BKNATIVE.openOAuth(target, stateKey, verifier, provider);
    } else {
      location.href = target;
    }
  }

  async function handleRedirect() {
    const url = new URL(location.href);
    const code = url.searchParams.get('code');
    const stateKey = url.searchParams.get('state');
    const err = url.searchParams.get('error');
    if (!code) return false;
    if (err) { toast('授權失敗：' + err); cleanup(); return true; }

    const raw = sessionStorage.getItem('bk_oauth_' + stateKey);
    if (raw) {
      // 與發起 OAuth 同一瀏覽上下文（桌面 / 手機瀏覽器 / iPhone Safari）：
      // 直接在此完成 token 交換，token 會存進當前頁面的 localStorage。
      const { provider, verifier } = JSON.parse(raw);
      sessionStorage.removeItem('bk_oauth_' + stateKey);
      // 清除網址列中的 code，避免重新整理重複交換
      history.replaceState({}, document.title, REDIRECT);
      try {
        const tok = await exchange(provider, code, verifier);
        applyToken(provider, tok);
        toast('已連接 ' + PROVIDERS[provider].name);
      } catch (e) {
        toast('連接失敗：' + (e.message || e));
      }
      return true;
    }

    // 不在同一瀏覽上下文（Android App 經由系統瀏覽器 / Chrome Custom Tabs 授權）：
    // 此時 token 若在此交換只會存進「系統瀏覽器」的 localStorage，App 的 WebView 讀不到，
    // 因此把授權碼經由自訂 scheme 回傳給 App 原生層，由原生層注入 BKOAuthBridge
    // 在「App 的 WebView」內完成交換，token 才會正確存進 App。
    const cb = 'billingtracker://oauth/callback?code=' + encodeURIComponent(code) +
               '&state=' + encodeURIComponent(stateKey);
    cleanup();
    location.href = cb;
    return true;
  }
  function cleanup() { history.replaceState({}, document.title, REDIRECT); }

  async function exchange(provider, code, verifier) {
    const p = PROVIDERS[provider];
    const body = new URLSearchParams({
      code, client_id: state.clientId, code_verifier: verifier,
      grant_type: 'authorization_code', redirect_uri: REDIRECT,
    });
    // #3 修復：從 sessionStorage 讀取 secret（不從 localStorage），用完即棄
    const cs = sessionStorage.getItem('bk_cs');
    if (cs) body.append('client_secret', cs);
    const res = await fetch(p.tokenUrl, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
    });
    if (!res.ok) { const t = await res.text(); throw new Error('token ' + res.status + ' ' + t); }
    return res.json();
  }
  function applyToken(provider, tok) {
    state.provider = provider;
    state.accessToken = tok.access_token;
    state.refreshToken = tok.refresh_token || state.refreshToken;
    state.expiresAt = Date.now() + (tok.expires_in || 3600) * 1000;
    // #3 修復：saveState 不再包含 clientSecret（因為根本沒存進去）
    saveState();
    refreshUI();
  }

  /* #12 修復：ensureToken 加入 in-flight 鎖，防止並發 refresh 導致 refresh token 失效
     JS 是 single-threaded 但 async/await 之間會 yield 事件迴圈，
     快速連續觸發「上傳+下載」可能同時送出兩次 refresh 請求 */
  let _refreshing = null;

  async function ensureToken() {
    if (tokenValid()) return state.accessToken;
    if (_refreshing) return _refreshing;  // 已有刷新在飛行中，直接共用同一個 Promise

    if (!state.refreshToken) {
      await disconnect(false);
      throw new Error('憑證已過期，請重新連接');
    }

    // 啟動刷新，鎖定 _refreshing
    _refreshing = _doRefresh().finally(() => { _refreshing = null; });
    return _refreshing;
  }

  /** 實際執行 token 刷新的內部函數 */
  async function _doRefresh() {
    const p = PROVIDERS[state.provider];
    const body = new URLSearchParams({
      grant_type: 'refresh_token', refresh_token: state.refreshToken, client_id: state.clientId,
    });
    // #3 修復：secret 從 sessionStorage 取
    const cs = sessionStorage.getItem('bk_cs');
    if (cs) body.append('client_secret', cs);
    const res = await fetch(p.tokenUrl, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body,
    });
    if (!res.ok) { await disconnect(false); throw new Error('重新整理失敗'); }
    const tok = await res.json();
    applyToken(state.provider, tok);
    return state.accessToken;
  }

  async function disconnect(notify = true) {
    state.accessToken = null; state.refreshToken = null; state.expiresAt = null;
    saveState();
    if (notify) toast('已斷線');
    refreshUI();
  }

  /* ---------- 上傳 / 下載 ---------- */
  async function upload() {
    let stage = '取得憑證';
    try {
      const tok = await ensureToken();
      stage = '上傳雲端';
      const data = window.BK.exportData();
      if (state.provider === 'drive') await uploadDrive(tok, data);
      else await uploadDropbox(tok, data);
      toast('備份已上傳至 ' + PROVIDERS[state.provider].name);
    } catch (e) { toast('上傳失敗（' + stage + '）：' + (e.message || e)); }
  }
  async function download() {
    let stage = '取得憑證';
    try {
      const tok = await ensureToken();
      stage = '讀取雲端檔案';
      const data = state.provider === 'drive' ? await downloadDrive(tok) : await downloadDropbox(tok);
      if (!data) { toast('雲端尚無備份檔，請先上傳一次'); return; }
      if (!confirm('從雲端還原將覆蓋目前本機資料，確定繼續？')) return;
      stage = '解析備份內容';
      window.BK.importData(data);
      toast('已從雲端還原');
    } catch (e) { toast('還原失敗（' + stage + '）：' + (e.message || e)); }
  }

  /* Google Drive */
  async function findDriveFile(tok) {
    const r = await fetch('https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id,name)',
      { headers: { 'Authorization': 'Bearer ' + tok } });
    if (!r.ok) throw new Error('Drive 清單 ' + r.status + ' ' + (await r.text()).slice(0, 120));
    const j = await r.json();
    return j.files || [];
  }
  async function uploadDrive(tok, data) {
    const files = await findDriveFile(tok);
    const existing = files.find(f => f.name === 'billkeeper_backup.json');
    let res;
    if (existing) {
      res = await fetch(`https://www.googleapis.com/upload/drive/v3/files/${existing.id}?uploadType=media`, {
        method: 'PATCH', headers: { 'Authorization': 'Bearer ' + tok, 'Content-Type': 'application/json' }, body: data,
      });
    } else {
      const boundary = '----billerboundary';
      const meta = { name: 'billkeeper_backup.json', parents: ['appDataFolder'] };
      const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}`
        + `\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${data}\r\n--${boundary}--\r\n`;
      res = await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id', {
        method: 'POST', headers: { 'Authorization': 'Bearer ' + tok, 'Content-Type': `multipart/related; boundary=${boundary}` }, body,
      });
    }
    if (!res.ok) throw new Error('Drive 上傳 ' + res.status + ' ' + (await res.text()).slice(0, 120));
  }
  async function downloadDrive(tok) {
    const files = await findDriveFile(tok);
    const f = files.find(x => x.name === 'billkeeper_backup.json');
    if (!f) return null;
    const r = await fetch(`https://www.googleapis.com/drive/v3/files/${f.id}?alt=media`, { headers: { 'Authorization': 'Bearer ' + tok } });
    if (!r.ok) throw new Error('Drive 下載 ' + r.status + ' ' + (await r.text()).slice(0, 120));
    return await r.text();
  }

  /* Dropbox */
  async function uploadDropbox(tok, data) {
    const res = await fetch('https://content.dropboxapi.com/2/files/upload', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + tok, 'Content-Type': 'application/octet-stream',
        'Dropbox-API-Arg': JSON.stringify({ path: '/billkeeper_backup.json', mode: 'overwrite', mute: true }),
      }, body: data,
    });
    if (!res.ok) throw new Error('Dropbox 上傳 ' + res.status + ' ' + (await res.text()).slice(0, 120));
  }
  async function downloadDropbox(tok) {
    const r = await fetch('https://content.dropboxapi.com/2/files/download', {
      method: 'POST',
      headers: { 'Authorization': 'Bearer ' + tok, 'Dropbox-API-Arg': JSON.stringify({ path: '/billkeeper_backup.json' }) },
    });
    if (r.status === 409) return null; // 檔案不存在
    if (!r.ok) throw new Error('Dropbox 下載 ' + r.status + ' ' + (await r.text()).slice(0, 120));
    return await r.text();
  }

  /* ---------- UI ---------- */
  function refreshUI() {
    const connected = !!state.accessToken && !!state.provider;
    const pName = state.provider ? PROVIDERS[state.provider].name : '';
    if ($('#cloudStatus')) $('#cloudStatus').textContent = connected ? `已連接：${pName}` : '尚未連接';
    if ($('#cloudConnectBtn')) $('#cloudConnectBtn').textContent = connected ? '重新連接' : '連接雲端';
    if ($('#cloudUploadBtn')) $('#cloudUploadBtn').disabled = !connected;
    if ($('#cloudDownloadBtn')) $('#cloudDownloadBtn').disabled = !connected;
    if ($('#cloudDisconnectBtn')) $('#cloudDisconnectBtn').disabled = !connected;
    if ($('#cloudProvider') && state.provider) $('#cloudProvider').value = state.provider;
    if ($('#cloudClientId') && state.clientId) $('#cloudClientId').value = state.clientId;
    if ($('#cloudRedirectHint')) $('#cloudRedirectHint').textContent = '重新導向網址：' + REDIRECT;
  }
  function bindUI() {
    $('#cloudConnectBtn').addEventListener('click', connect);
    $('#cloudUploadBtn').addEventListener('click', upload);
    $('#cloudDownloadBtn').addEventListener('click', download);
    $('#cloudDisconnectBtn').addEventListener('click', () => disconnect(true));
    refreshUI();
  }

  /* ---------- 供 Android 原生層回傳 OAuth 結果 ----------
     授權頁改由系統瀏覽器 / Chrome Custom Tabs 開啟（見 connect()），
     完成後 Google 重定向到 REDIRECT（本託管頁），該頁在「非 WebView」環境下
     會以自訂 scheme billingtracker://oauth/callback 把 code/state 回傳給 App，
     MainActivity.onNewIntent 收到後呼叫此函式完成 token 交換。 */
  window.BKOAuthBridge = async function (code, stateKey, err, verifier, provider) {
    nativeLog('BKOAuthBridge called code=' + (code ? code.slice(0,6) + '…' : 'null') + ' provider=' + provider);
    setStatus('授權處理中…請稍候');   // v3.29：一進入立刻顯示進度，確保使用者看得到（非靜默）
    if (!code) {
      const m = err ? ('授權失敗：' + err) : '授權已取消';
      setStatus(m); toast(m); cleanup(); return;
    }
    // 優先用原生層經 billingtracker:// 回傳時附帶的 verifier/provider（不依賴 WebView sessionStorage，
    // 即使回傳過程中 WebView 被重建也能完成 token 交換）。若原生未提供，退回同上下文的 sessionStorage。
    if (!verifier || !provider) {
      const raw = sessionStorage.getItem('bk_oauth_' + stateKey);
      if (!raw) { const m = '找不到授權資訊，請重新連接'; setStatus(m); alert(m); cleanup(); return; }
      const o = JSON.parse(raw);
      verifier = o.verifier; provider = o.provider;
    }
    try {
      nativeLog('exchange start provider=' + provider);
      const tok = await exchange(provider, code, verifier);
      nativeLog('exchange ok access_token=' + (!!tok && !!tok.access_token));
      applyToken(provider, tok);
      const m = '已連接 ' + PROVIDERS[provider].name;
      setStatus(m); toast(m);
    } catch (e) {
      // 錯誤同時寫入雲端狀態區（一定看得到）+ alert（截圖用）+ logcat，確保無論 alert 是否彈出都能定位。
      const msg = '連接失敗：' + (e && (e.message || e.toString()) || '未知錯誤');
      nativeLog('exchange error: ' + msg);
      setStatus(msg + '（請截圖回報）');
      alert(msg + '\n\n若持續失敗，請截圖此訊息回報。');
    }
  };

  window.Cloud = {
    async init() {
      const handled = await handleRedirect();
      if (!handled) refreshUI();
      bindUI();
    },
    refreshUI,
  };
})();
