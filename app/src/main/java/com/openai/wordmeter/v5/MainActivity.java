package com.openai.wordmeter.v5;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String RELEASE_API_URL = "https://api.github.com/repos/shilan660/-/releases/latest";
    private static final String PREFS_NAME = "maimemo_toolbox_updater";
    private static final String PREF_PENDING_DOWNLOAD_ID = "pending_download_id";
    private static final String PREF_PENDING_INSTALL_URI = "pending_install_uri";
    private static final int REQUEST_UNKNOWN_SOURCES = 7401;

    private WebView webView;
    private SharedPreferences updaterPrefs;
    private BroadcastReceiver downloadReceiver;
    private long pendingDownloadId = -1L;
    private Uri pendingInstallUri;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(13, 15, 19));
        getWindow().setNavigationBarColor(Color.rgb(13, 15, 19));
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        updaterPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        pendingDownloadId = updaterPrefs.getLong(PREF_PENDING_DOWNLOAD_ID, -1L);
        String savedInstallUri = updaterPrefs.getString(PREF_PENDING_INSTALL_URI, null);
        if (savedInstallUri != null) {
            pendingInstallUri = Uri.parse(savedInstallUri);
        }
        registerDownloadReceiver();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(13, 15, 19));
        webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.addJavascriptInterface(new UpdateBridge(), "AndroidUpdater");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        try {
            String html = readAssetUtf8("index.html");
            // Use the API's origin as the base URL so the existing same-origin API requests keep working.
            webView.loadDataWithBaseURL(
                    "https://open.maimemo.com/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
            );
        } catch (IOException e) {
            TextView error = new TextView(this);
            error.setTextColor(Color.WHITE);
            error.setBackgroundColor(Color.rgb(13, 15, 19));
            error.setPadding(32, 48, 32, 32);
            error.setText("工具箱页面读取失败。请重新安装应用。\n\n" + e.getMessage());
            setContentView(error);
        }
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    return;
                }
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (completedId == pendingDownloadId) {
                    handleDownloadComplete(completedId);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private void handleDownloadComplete(long downloadId) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null || downloadId < 0) {
            notifyDownloadState("download_failed", "无法读取系统下载服务，请稍后重试。");
            clearPendingDownload();
            return;
        }

        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (statusColumn < 0) {
                return;
            }
            int status = cursor.getInt(statusColumn);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                Uri downloadedUri = manager.getUriForDownloadedFile(downloadId);
                clearPendingDownload();
                if (downloadedUri == null) {
                    notifyDownloadState("download_failed", "APK 已下载，但系统没有返回文件地址。请重新检查更新。");
                    return;
                }
                pendingInstallUri = downloadedUri;
                updaterPrefs.edit().putString(PREF_PENDING_INSTALL_URI, downloadedUri.toString()).apply();
                runOnUiThread(this::installPendingApk);
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reasonColumn = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                int reason = reasonColumn >= 0 ? cursor.getInt(reasonColumn) : 0;
                clearPendingDownload();
                notifyDownloadState("download_failed", "下载失败（代码 " + reason + "）。请检查网络后重试。");
            }
        } catch (Exception e) {
            clearPendingDownload();
            notifyDownloadState("download_failed", "读取下载结果失败：" + safeMessage(e));
        }
    }

    private void clearPendingDownload() {
        pendingDownloadId = -1L;
        updaterPrefs.edit().remove(PREF_PENDING_DOWNLOAD_ID).apply();
    }

    private void installPendingApk() {
        if (pendingInstallUri == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            notifyDownloadState("permission_required", "APK 已下载。请允许“墨墨工具箱”安装未知来源应用，然后返回这里继续安装。");
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(settingsIntent, REQUEST_UNKNOWN_SOURCES);
            } catch (Exception e) {
                notifyDownloadState("permission_required", "请在系统设置中允许“墨墨工具箱”安装未知来源应用，然后点击“继续安装”。");
            }
            return;
        }

        try {
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(pendingInstallUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.setClipData(ClipData.newRawUri("APK update", pendingInstallUri));
            startActivity(installIntent);
            pendingInstallUri = null;
            updaterPrefs.edit().remove(PREF_PENDING_INSTALL_URI).apply();
            notifyDownloadState("installing", "已打开系统安装界面。请确认安装新版墨墨工具箱。");
        } catch (Exception e) {
            notifyDownloadState("install_failed", "无法打开系统安装界面：" + safeMessage(e) + "。可以点击“重试安装”。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallUri == null && updaterPrefs != null) {
            String saved = updaterPrefs.getString(PREF_PENDING_INSTALL_URI, null);
            if (saved != null) {
                pendingInstallUri = Uri.parse(saved);
            }
        }
        if (pendingInstallUri != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                installPendingApk();
            }
        } else if (pendingDownloadId > 0) {
            handleDownloadComplete(pendingDownloadId);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNKNOWN_SOURCES && pendingInstallUri != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                installPendingApk();
            } else {
                notifyDownloadState("permission_required", "尚未允许安装。开启系统授权后，返回本应用并点击“继续安装”。");
            }
        }
    }

    public final class UpdateBridge {
        @JavascriptInterface
        public String getCurrentVersionName() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void checkForUpdates() {
            Thread thread = new Thread(() -> {
                JSONObject result = new JSONObject();
                try {
                    JSONObject release = requestLatestRelease();
                    String tag = release.optString("tag_name", "").trim();
                    String latestVersion = tag.replaceFirst("^[vV]", "");
                    String currentVersion = BuildConfig.VERSION_NAME;
                    if (latestVersion.isEmpty() || versionParts(latestVersion).length == 0) {
                        throw new IOException("GitHub Release 的版本标签格式不正确。");
                    }

                    result.put("currentVersion", currentVersion);
                    result.put("latestVersion", latestVersion);
                    result.put("notes", release.optString("body", "").trim());

                    if (compareVersions(latestVersion, currentVersion) <= 0) {
                        result.put("status", "up_to_date");
                        result.put("message", "已经是最新版本。");
                    } else {
                        String apkUrl = findApkUrl(release.optJSONArray("assets"));
                        if (apkUrl == null) {
                            result.put("status", "error");
                            result.put("message", "发现新版本，但该 GitHub Release 中没有 APK 附件。请稍后重试。");
                        } else {
                            result.put("status", "update_available");
                            result.put("downloadUrl", apkUrl);
                            result.put("message", "发现新版本，可以下载并安装。");
                        }
                    }
                } catch (Exception e) {
                    try {
                        result.put("status", "error");
                        result.put("currentVersion", BuildConfig.VERSION_NAME);
                        if (e instanceof ReleaseNotFoundException) {
                            result.put("message", "还没有找到正式版 Release。请先在 GitHub 发布一个带 APK 附件的版本，再回来检查更新。");
                        } else {
                            result.put("message", "检查更新失败：" + safeMessage(e));
                        }
                    } catch (Exception ignored) {
                        // Keep the fallback message below if JSON construction unexpectedly fails.
                    }
                }
                postUpdateCheckResult(result);
            }, "maimemo-update-check");
            thread.start();
        }

        @JavascriptInterface
        public void downloadAndInstall(String downloadUrl) {
            if (!isAllowedReleaseUrl(downloadUrl)) {
                notifyDownloadState("download_failed", "下载地址无效。请重新检查更新。");
                return;
            }
            runOnUiThread(() -> {
                if (pendingInstallUri != null) {
                    installPendingApk();
                    return;
                }
                if (pendingDownloadId > 0) {
                    notifyDownloadState("downloading", "正在下载新版 APK，请留意系统通知栏中的进度。");
                    return;
                }
                try {
                    DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager == null) {
                        notifyDownloadState("download_failed", "系统下载服务不可用，请稍后重试。");
                        return;
                    }
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                    request.setTitle("墨墨工具箱更新");
                    request.setDescription("正在下载新版 APK");
                    request.setMimeType("application/vnd.android.package-archive");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setAllowedOverMetered(true);
                    request.setAllowedOverRoaming(false);
                    request.setDestinationInExternalFilesDir(
                            MainActivity.this,
                            Environment.DIRECTORY_DOWNLOADS,
                            "maimemo-toolbox-update-" + System.currentTimeMillis() + ".apk"
                    );
                    pendingDownloadId = manager.enqueue(request);
                    updaterPrefs.edit().putLong(PREF_PENDING_DOWNLOAD_ID, pendingDownloadId).apply();
                    notifyDownloadState("downloading", "已开始下载新版 APK。下载完成后会自动打开系统安装界面。");
                } catch (Exception e) {
                    clearPendingDownload();
                    notifyDownloadState("download_failed", "启动下载失败：" + safeMessage(e));
                }
            });
        }
    }

    private JSONObject requestLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "MaimemoToolbox-Android-Updater");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readStreamUtf8(stream);
            if (status == 404) {
                throw new ReleaseNotFoundException();
            }
            if (status < 200 || status >= 300) {
                throw new IOException("GitHub 返回 HTTP " + status + (body.isEmpty() ? "" : "：" + body));
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private String findApkUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (name.toLowerCase().endsWith(".apk") && isAllowedReleaseUrl(url)) {
                return url;
            }
        }
        return null;
    }

    private boolean isAllowedReleaseUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            String host = uri.getHost();
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(host)
                    && path != null
                    && path.startsWith("/shilan660/-/releases/download/");
        } catch (Exception e) {
            return false;
        }
    }

    private static int compareVersions(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return av < bv ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] versionParts(String value) {
        Matcher matcher = Pattern.compile("\\d+").matcher(value == null ? "" : value);
        java.util.ArrayList<Integer> parts = new java.util.ArrayList<>();
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException e) {
                parts.add(Integer.MAX_VALUE);
            }
        }
        int[] result = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            result[i] = parts.get(i);
        }
        return result;
    }

    private void postUpdateCheckResult(JSONObject result) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "if(window.handleUpdateCheckResult){window.handleUpdateCheckResult(" + result.toString() + ");}",
                        null
                );
            }
        });
    }

    private void notifyDownloadState(String status, String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("status", status);
            payload.put("message", message);
        } catch (Exception ignored) {
            return;
        }
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "if(window.handleUpdateDownloadState){window.handleUpdateDownloadState(" + payload.toString() + ");}",
                        null
                );
            }
        });
    }

    private static String readStreamUtf8(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class ReleaseNotFoundException extends IOException {
        ReleaseNotFoundException() {
            super("Release not found");
        }
    }

    private String readAssetUtf8(String name) throws IOException {
        try (InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "(function(){var ids=['homePage','speedPage','calcPage','foodPage'];" +
                "var active=ids.find(function(id){var e=document.getElementById(id);" +
                "return e&&getComputedStyle(e).display!=='none';});" +
                "if(active&&active!=='homePage'&&typeof window.showPage==='function'){" +
                "window.showPage('homePage');return 'handled';}return 'exit';})()",
                value -> {
                    if ("\"exit\"".equals(value) || value == null) {
                        finish();
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already unregistered.
            }
            downloadReceiver = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.removeJavascriptInterface("AndroidUpdater");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
