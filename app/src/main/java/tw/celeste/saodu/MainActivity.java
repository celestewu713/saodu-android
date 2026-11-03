package tw.celeste.saodu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 掃毒小幫手 Android 版：掃描手機上已安裝的 APP。
 *
 * 作法：算出每個 APP 安裝檔（APK）的 SHA-256 指紋，交給雲端版掃毒小幫手
 * （Cloudflare Workers，保管 VirusTotal 金鑰）查詢。APP 本身不存金鑰，只存親友密碼。
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_SERVER = "https://saodu.celestewu713.workers.dev";
    private static final long QUERY_INTERVAL_MS = 16_000;   // VirusTotal 免費版每分鐘 4 次
    private static final long CACHE_MS = 7L * 24 * 60 * 60 * 1000;

    private static final int GREEN = Color.parseColor("#1F6F5C");
    private static final int TEXT = Color.parseColor("#1C2421");
    private static final int MUTED = Color.parseColor("#5B6863");
    private static final int LINE = Color.parseColor("#D7DFDB");
    private static final int DANGER = Color.parseColor("#B3261E");
    private static final int DANGER_BG = Color.parseColor("#FBE7E5");
    private static final int WARN = Color.parseColor("#9A6200");
    private static final int WARN_BG = Color.parseColor("#FDF3DC");
    private static final int CLEAN = Color.parseColor("#1F7A45");
    private static final int UNKNOWN = Color.parseColor("#3D5A80");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SharedPreferences cache;

    private EditText serverInput;
    private EditText passwordInput;     // 登入頁的密碼欄
    private TextView loginError;
    private CheckBox rememberCheck;
    private Button loginButton;
    private View loginPage;
    private View scanPage;
    private CheckBox systemCheck;
    private Button startButton;
    private Button stopButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout resultList;

    private volatile boolean cancelled = false;
    private Thread worker;

    // ------------------------------------------------------------------ 畫面

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        cache = getSharedPreferences("scan_cache", MODE_PRIVATE);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        loginPage = buildLoginPage();
        scanPage = buildLayout();
        frame.addView(loginPage);
        frame.addView(scanPage);
        setContentView(frame);
        // 每次打開都先顯示登入頁；有記住密碼時會自動填好，按「進入」即可
        showPage(false);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout.LayoutParams wide(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable g = box(primary ? GREEN : Color.WHITE, primary ? GREEN : LINE);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private EditText input(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = box(Color.WHITE, LINE);
        g.setCornerRadius(dp(10));
        e.setBackground(g);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F7F6"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root);

        root.addView(text("掃毒小幫手", 24, TEXT, true));
        root.addView(text("檢查手機上安裝的每一個 APP，用 70 多家防毒引擎做第二意見。", 14, MUTED, false));

        TextView advToggle = text("進階設定 ▸", 14, GREEN, false);
        root.addView(advToggle, wide(12));
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        adv.addView(text("伺服器網址", 14, MUTED, false));
        serverInput = input("https://…", prefs.getString("server", DEFAULT_SERVER), false);
        adv.addView(serverInput, wide(4));
        systemCheck = new CheckBox(this);
        systemCheck.setText("也檢查手機內建的系統 APP（數量多，會久很多）");
        systemCheck.setTextColor(TEXT);
        adv.addView(systemCheck, wide(8));
        TextView logout = text("更換親友密碼", 14, GREEN, false);
        logout.setOnClickListener(v -> {
            if (worker != null && worker.isAlive()) {
                toastDialog("請先停止掃描，再更換密碼。");
                return;
            }
            prefs.edit().remove("password").remove("saved_password").apply();
            showPage(false);
        });
        adv.addView(logout, wide(12));
        root.addView(adv, wide(4));
        advToggle.setOnClickListener(v -> {
            boolean show = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(show ? "進階設定 ▾" : "進階設定 ▸");
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("開始掃描", true);
        stopButton = button("停止", false);
        stopButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(52), 1);
        actions.addView(startButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(52), 1);
        half2.leftMargin = dp(8);
        actions.addView(stopButton, half2);
        root.addView(actions, wide(16));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, wide(12));

        statusText = text("按「開始掃描」後，會逐一檢查你自己安裝的 APP。\n免費版每分鐘只能查 4 個，第一次掃描可能要十幾分鐘；查過的 APP 7 天內不會重查。", 14, MUTED, false);
        root.addView(statusText, wide(8));

        resultList = new LinearLayout(this);
        resultList.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultList, wide(12));

        Button exitButton = button("離開 APP", false);
        exitButton.setOnClickListener(v -> confirmExit());
        root.addView(exitButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) {{
            topMargin = dp(24);
        }});

        TextView foot = text("這是「第二意見」工具，日常防護請保留 Google Play 安全防護。", 12, MUTED, false);
        root.addView(foot, wide(16));

        startButton.setOnClickListener(v -> startScan());
        stopButton.setOnClickListener(v -> {
            cancelled = true;
            statusText.setText("正在停止…");
        });
        return scroll;
    }

    // ------------------------------------------------------------------ 登入頁

    private View buildLoginPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F5F7F6"));
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));

        TextView icon = text("🛡️", 48, TEXT, false);
        icon.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(icon, wide(0));
        TextView title = text("掃毒小幫手", 26, TEXT, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, wide(8));
        TextView sub = text("請輸入親友密碼", 15, MUTED, false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub, wide(4));

        passwordInput = input("密碼請向 Celeste 索取", "", true);
        root.addView(passwordInput, wide(24));
        rememberCheck = new CheckBox(this);
        rememberCheck.setText("記住密碼（下次自動填入）");
        rememberCheck.setTextColor(TEXT);
        rememberCheck.setChecked(prefs.getBoolean("remember", true));
        root.addView(rememberCheck, wide(8));
        loginError = text("", 14, DANGER, false);
        loginError.setVisibility(View.GONE);
        root.addView(loginError, wide(8));
        loginButton = button("進入", true);
        root.addView(loginButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) {{
            topMargin = dp(16);
        }});
        loginButton.setOnClickListener(v -> checkPassword());
        Button exitButton = button("離開 APP", false);
        exitButton.setOnClickListener(v -> confirmExit());
        root.addView(exitButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) {{
            topMargin = dp(12);
        }});
        passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            checkPassword();
            return true;
        });
        return scroll;
    }

    private void showPage(boolean scan) {
        loginPage.setVisibility(scan ? View.GONE : View.VISIBLE);
        scanPage.setVisibility(scan ? View.VISIBLE : View.GONE);
        if (!scan) {
            boolean remember = prefs.getBoolean("remember", true);
            rememberCheck.setChecked(remember);
            passwordInput.setText(remember ? prefs.getString("saved_password", prefs.getString("password", "")) : "");
            loginButton.setEnabled(true);
            loginButton.setText("進入");
        }
    }

    private void checkPassword() {
        String password = passwordInput.getText().toString();
        if (password.isEmpty()) {
            loginError.setText("請輸入密碼。");
            loginError.setVisibility(View.VISIBLE);
            return;
        }
        String server = prefs.getString("server", DEFAULT_SERVER).replaceAll("/+$", "");
        loginButton.setEnabled(false);
        loginButton.setText("確認中…");
        loginError.setVisibility(View.GONE);
        new Thread(() -> {
            HttpResult r = request(server + "/api/login", "POST", password);
            ui.post(() -> {
                if (r.status == 200) {
                    boolean remember = rememberCheck.isChecked();
                    SharedPreferences.Editor e = prefs.edit()
                            .putString("password", password)          // 這次使用中的密碼
                            .putBoolean("remember", remember);
                    if (remember) e.putString("saved_password", password);
                    else e.remove("saved_password");
                    e.apply();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(passwordInput.getWindowToken(), 0);
                    showPage(true);
                    return;
                }
                loginButton.setEnabled(true);
                loginButton.setText("進入");
                loginError.setText(r.status == 401 ? "密碼不正確，請再試一次。"
                        : r.status == 429 ? "密碼錯誤太多次，請 10 分鐘後再試。"
                        : r.status == 0 ? "連不上伺服器，請確認網路。"
                        : "伺服器回應錯誤（" + r.status + "）。");
                loginError.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    // ------------------------------------------------------------------ 掃描

    private static class AppItem {
        String label, pkg, apk, sha256, verdict = "unknown", source = "";
        int malicious, suspicious;
        List<String> engines = new ArrayList<>();
        long updated;
        boolean system;
    }

    private void startScan() {
        String server = serverInput.getText().toString().trim().replaceAll("/+$", "");
        String password = prefs.getString("password", "");
        if (password.isEmpty()) {
            showPage(false);
            return;
        }
        if (!server.startsWith("https://")) {
            toastDialog("伺服器網址要以 https:// 開頭。");
            return;
        }
        prefs.edit().putString("server", server).apply();

        cancelled = false;
        startButton.setEnabled(false);
        startButton.setAlpha(0.5f);
        stopButton.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        resultList.removeAllViews();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        boolean includeSystem = systemCheck.isChecked();
        worker = new Thread(() -> runScan(server, password, includeSystem));
        worker.start();
    }

    private void runScan(String server, String password, boolean includeSystem) {
        List<AppItem> apps = new ArrayList<>();
        String finalMessage;
        try {
            setStatus("正在列出手機上的 APP…");
            apps = listApps(includeSystem);
            int total = apps.size();
            ui.post(() -> progressBar.setMax(Math.max(1, total)));
            long lastQuery = 0;
            int queried = 0;

            for (int i = 0; i < total && !cancelled; i++) {
                AppItem app = apps.get(i);
                final int n = i + 1;
                ui.post(() -> progressBar.setProgress(n));

                String cacheKey = app.pkg + "@" + app.updated;
                JSONObject cached = readCache(cacheKey);
                if (cached != null) {
                    applyResult(app, cached);
                    app.source = "快取";
                    continue;
                }

                long remainingNew = countUncached(apps, i);
                setStatus("第 " + n + " / " + total + " 個：" + app.label
                        + "\n約還需要 " + Math.max(1, remainingNew * QUERY_INTERVAL_MS / 60000) + " 分鐘（螢幕會保持開啟）");

                try {
                    app.sha256 = sha256(app.apk);
                } catch (Exception e) {
                    app.verdict = "skipped";
                    app.source = "無法讀取安裝檔";
                    continue;
                }

                JSONObject result = null;
                for (int attempt = 0; attempt < 4 && !cancelled; attempt++) {
                    long wait = QUERY_INTERVAL_MS - (System.currentTimeMillis() - lastQuery);
                    if (lastQuery > 0 && wait > 0) sleepQuietly(wait);
                    if (cancelled) break;
                    lastQuery = System.currentTimeMillis();
                    HttpResult r = query(server, password, app.sha256);
                    if (r.status == 200) {
                        result = new JSONObject(r.body);
                        break;
                    } else if (r.status == 401) {
                        throw new AuthException();
                    } else if (r.status == 429) {
                        setStatus("查詢額度暫時用完，等一分鐘後繼續…（" + app.label + "）");
                        sleepQuietly(60_000);
                    } else {
                        app.source = "查詢失敗（" + (r.status == 0 ? "網路問題" : "代碼 " + r.status) + "）";
                        sleepQuietly(3_000);
                    }
                }
                if (result == null) {
                    app.verdict = "skipped";
                    if (app.source.isEmpty()) app.source = "未完成查詢";
                    continue;
                }
                queried++;
                applyResult(app, result);
                app.source = "VirusTotal";
                writeCache(cacheKey, result);
            }
            finalMessage = cancelled ? "已停止。已檢查的結果如下，下次掃描會接著查。" : "掃描完成！";
        } catch (AuthException e) {
            finalMessage = "親友密碼已變更，請重新輸入。";
            ui.post(() -> {
                prefs.edit().remove("password").remove("saved_password").apply();
                showPage(false);
                loginError.setText("親友密碼已變更，請重新輸入。");
                loginError.setVisibility(View.VISIBLE);
            });
        } catch (Exception e) {
            finalMessage = "發生錯誤：" + e.getMessage();
        }

        final List<AppItem> done = apps;
        final String msg = finalMessage;
        ui.post(() -> showResults(done, msg));
    }

    private long countUncached(List<AppItem> apps, int from) {
        long c = 0;
        for (int j = from; j < apps.size(); j++) {
            AppItem a = apps.get(j);
            if (readCache(a.pkg + "@" + a.updated) == null) c++;
        }
        return c;
    }

    @SuppressWarnings("deprecation")
    private List<AppItem> listApps(boolean includeSystem) {
        PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(0);
        List<AppItem> list = new ArrayList<>();
        for (PackageInfo p : pkgs) {
            ApplicationInfo ai = p.applicationInfo;
            if (ai == null || ai.sourceDir == null) continue;
            if (p.packageName.equals(getPackageName())) continue;
            boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (system && !includeSystem) continue;
            AppItem a = new AppItem();
            a.pkg = p.packageName;
            a.label = String.valueOf(ai.loadLabel(pm));
            a.apk = ai.sourceDir;
            a.updated = p.lastUpdateTime;
            a.system = system;
            list.add(a);
        }
        // 最近安裝或更新的 APP 優先檢查
        Collections.sort(list, (x, y) -> Long.compare(y.updated, x.updated));
        return list;
    }

    private static String sha256(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static class HttpResult {
        int status;
        String body = "";
    }

    private static class AuthException extends Exception {
    }

    private HttpResult query(String server, String password, String sha) {
        return request(server + "/api/file/" + sha, "GET", password);
    }

    private HttpResult request(String url, String method, String password) {
        HttpResult r = new HttpResult();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
            }
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            // 與網頁版相同：密碼先做 URL 編碼，中文密碼也能放進標頭
            c.setRequestProperty("X-App-Password", URLEncoder.encode(password, "UTF-8").replace("+", "%20"));
            c.setRequestProperty("Accept", "application/json");
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                r.body = out.toString("UTF-8");
            }
        } catch (Exception e) {
            r.status = 0;
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private void applyResult(AppItem app, JSONObject o) {
        if (!o.optBoolean("found", false)) {
            app.verdict = "unknown";
            return;
        }
        app.verdict = o.optString("verdict", "unknown");
        app.malicious = o.optInt("malicious", 0);
        app.suspicious = o.optInt("suspicious", 0);
        JSONArray d = o.optJSONArray("detections");
        app.engines.clear();
        if (d != null) {
            for (int i = 0; i < d.length() && i < 8; i++) {
                JSONObject e = d.optJSONObject(i);
                if (e != null) app.engines.add(e.optString("engine") + "：" + e.optString("result"));
            }
        }
    }

    private JSONObject readCache(String key) {
        String raw = cache.getString(key, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (System.currentTimeMillis() - o.optLong("_t", 0) > CACHE_MS) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String key, JSONObject result) {
        try {
            result.put("_t", System.currentTimeMillis());
            cache.edit().putString(key, result.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ 結果

    private void showResults(List<AppItem> apps, String message) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setEnabled(true);
        startButton.setAlpha(1f);
        stopButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        int danger = 0, warning = 0, clean = 0, unknown = 0, skipped = 0;
        List<AppItem> bad = new ArrayList<>();
        List<AppItem> unknownList = new ArrayList<>();
        for (AppItem a : apps) {
            switch (a.verdict) {
                case "danger": danger++; bad.add(a); break;
                case "warning": warning++; bad.add(a); break;
                case "clean": clean++; break;
                case "skipped": skipped++; break;
                default: unknown++; unknownList.add(a);
            }
        }
        statusText.setText(message + "\n共 " + apps.size() + " 個 APP：⛔ 很可能有毒 " + danger + "、⚠️ 請小心 " + warning
                + "、✅ 安全 " + clean + "、❔ 查無紀錄 " + unknown + (skipped > 0 ? "、⏭ 未檢查 " + skipped : ""));
        statusText.setTextColor(TEXT);

        resultList.removeAllViews();
        if (bad.isEmpty() && danger + warning == 0 && clean > 0) {
            TextView ok = text("✅ 沒有發現有問題的 APP。", 16, CLEAN, true);
            resultList.addView(ok, wide(4));
        }
        if (!bad.isEmpty()) {
            resultList.addView(text("需要注意的 APP", 17, TEXT, true), wide(8));
            for (AppItem a : bad) resultList.addView(card(a, true), wide(8));
        }
        if (!unknownList.isEmpty()) {
            TextView t = text("VirusTotal 沒看過的 APP（" + unknownList.size() + " 個）▸", 15, GREEN, false);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setVisibility(View.GONE);
            box.addView(text("通常是較新或較少人用的 APP。如果是從 Google Play 下載的，一般沒問題；不認得來源的建議移除。", 13, MUTED, false));
            for (AppItem a : unknownList) box.addView(card(a, false), wide(8));
            t.setOnClickListener(v -> box.setVisibility(box.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            resultList.addView(t, wide(16));
            resultList.addView(box, wide(4));
        }
    }

    private View card(AppItem a, boolean actions) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        int fill = "danger".equals(a.verdict) ? DANGER_BG : "warning".equals(a.verdict) ? WARN_BG : Color.WHITE;
        int stroke = "danger".equals(a.verdict) ? DANGER : "warning".equals(a.verdict) ? WARN : LINE;
        c.setBackground(box(fill, stroke));

        String head = "danger".equals(a.verdict) ? "⛔ 很可能有毒（" + a.malicious + " 家判定惡意）"
                : "warning".equals(a.verdict) ? "⚠️ 請小心（" + a.malicious + " 家惡意、" + a.suspicious + " 家可疑）"
                : "❔ 查無紀錄";
        int headColor = "danger".equals(a.verdict) ? DANGER : "warning".equals(a.verdict) ? WARN : UNKNOWN;
        c.addView(text(head, 14, headColor, true));
        c.addView(text(a.label, 16, TEXT, true));
        c.addView(text(a.pkg + (a.system ? "（系統 APP）" : ""), 12, MUTED, false));
        if (!a.engines.isEmpty()) c.addView(text(TextUtils.join("、", a.engines), 12, MUTED, false));

        if (actions) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.START);
            Button uninstall = button("解除安裝", false);
            uninstall.setTextColor(DANGER);
            uninstall.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg));
                startActivity(i);
            });
            row.addView(uninstall, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
            if (a.sha256 != null) {
                Button detail = button("查看報告", false);
                detail.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.virustotal.com/gui/file/" + a.sha256))));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
                lp.leftMargin = dp(8);
                row.addView(detail, lp);
            }
            c.addView(row, wide(8));
        }
        return c;
    }

    // ------------------------------------------------------------------ 小工具

    private void setStatus(String s) {
        ui.post(() -> {
            statusText.setText(s);
            statusText.setTextColor(MUTED);
        });
    }

    private void sleepQuietly(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!cancelled && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(Math.min(500, end - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void confirmExit() {
        if (worker != null && worker.isAlive()) {
            new AlertDialog.Builder(this)
                    .setMessage("掃描還在進行中，確定要離開嗎？\n已經查過的 APP 會保留紀錄，下次掃描會接著查。")
                    .setPositiveButton("離開", (d, w) -> exitApp())
                    .setNegativeButton("繼續掃描", null)
                    .show();
        } else {
            exitApp();
        }
    }

    private void exitApp() {
        cancelled = true;
        finishAndRemoveTask();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // 掃描中按返回鍵也先確認，避免不小心中斷
        confirmExit();
    }

    private void toastDialog(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("好", null).show();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }
}
