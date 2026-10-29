package com.charlichaap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * Charlie Chaap Android client.
 *
 * Two modes:
 *  - Connected: loads the customer site from the restaurant's LAN server
 *    (e.g. http://192.168.1.25:3000/customer), so orders are shared with
 *    every device on the same Wi-Fi.
 *  - Offline: if no server address is saved (or a bundled menu exists), the
 *    app opens the locally bundled menu page, which keeps the cart in the
 *    phone itself.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "charlichaap";
    private static final String KEY_SERVER = "server_url";
    private static final String ASSET_MENU = "index.html";

    private WebView web;
    private SharedPreferences prefs;
    private boolean assetMenuAvailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        assetMenuAvailable = assetExists(ASSET_MENU);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();
        loadInitial();
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        try {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {
            // Not available on every API level; the bundled page still works.
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")) {
                    return false;
                }
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showConnectionError();
                }
            }
        });
    }

    // ------------------------------------------------------------------ loading

    private void loadInitial() {
        String server = prefs.getString(KEY_SERVER, "");
        if (server != null && !server.isEmpty()) {
            web.loadUrl(server);
        } else if (assetMenuAvailable) {
            web.loadUrl("file:///android_asset/" + ASSET_MENU);
            toast("Offline menu. Set the server address to share orders.");
        } else {
            showSetupScreen();
        }
    }

    private void showConnectionError() {
        String server = prefs.getString(KEY_SERVER, "");
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;padding:28px;color:#2b1a10;background:#fffaf4}"
                + "h2{margin:0 0 10px}p{line-height:1.5;color:#5c4636}"
                + "code{background:#f2e6d8;padding:2px 5px;border-radius:4px}"
                + "a{display:inline-block;margin-top:18px;background:#c2410c;color:#fff;"
                + "padding:12px 18px;border-radius:10px;text-decoration:none}</style></head><body>"
                + "<h2>Can't reach the order desk</h2>"
                + "<p>Tried <code>" + (server == null ? "" : server) + "</code></p>"
                + "<p>Check that the server computer is switched on and running <code>npm start</code>, "
                + "and that this phone is on the same Wi-Fi network.</p>"
                + "<p>Tap the <b>&#8942;</b> menu and choose <b>Reload</b> once the server is up.</p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void showSetupScreen() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;padding:28px;color:#2b1a10;background:#fffaf4}"
                + "h2{margin:0 0 10px}p{line-height:1.55;color:#5c4636}"
                + "code{background:#f2e6d8;padding:2px 5px;border-radius:4px}</style></head><body>"
                + "<h2>Welcome to Charlie Chaap</h2>"
                + "<p>Open the <b>&#8942;</b> menu and choose <b>Server address</b> to connect this phone "
                + "to the restaurant order desk, for example <code>http://192.168.1.25:3000</code>.</p>"
                + "<p>The address is printed in the server terminal when you run <code>npm start</code>.</p>"
                + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Server address");
        menu.add(0, 2, 1, "Reload");
        if (assetMenuAvailable) {
            menu.add(0, 3, 2, "Offline menu");
        }
        menu.add(0, 4, 3, "About");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                askServerAddress();
                return true;
            case 2:
                web.reload();
                return true;
            case 3:
                web.loadUrl("file:///android_asset/" + ASSET_MENU);
                return true;
            case 4:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void askServerAddress() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.25:3000");
        String current = prefs.getString(KEY_SERVER, "");
        if (current != null && !current.isEmpty()) {
            input.setText(current);
            input.setSelection(input.getText().length());
        }

        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                getResources().getDisplayMetrics());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText("Enter the LAN address of the order desk computer.");
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, 0, 0, pad / 2);
        box.addView(hint);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Server address")
                .setView(box)
                .setPositiveButton("Connect", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String url = normalise(input.getText().toString());
                        if (url.isEmpty()) {
                            toast("Enter a server address");
                            return;
                        }
                        prefs.edit().putString(KEY_SERVER, url).apply();
                        web.loadUrl(url);
                    }
                })
                .setNeutralButton("Offline menu", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().remove(KEY_SERVER).apply();
                        if (assetMenuAvailable) {
                            web.loadUrl("file:///android_asset/" + ASSET_MENU);
                        } else {
                            showSetupScreen();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        String version = "1.0";
        long code = 0;
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("Charlie Chaap")
                .setMessage("Offline LAN menu & order desk\n\nVersion " + version + " (build " + code + ")\n\n"
                        + "Install a newer APK over this one to update; orders and settings stay on the server.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------ helpers

    private static String normalise(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        Uri uri = Uri.parse(s);
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            s = s + "/customer";
        }
        return s;
    }

    private boolean assetExists(String name) {
        try {
            String[] list = getAssets().list("");
            if (list == null) return false;
            for (String entry : list) {
                if (name.equals(entry)) return true;
            }
            InputStream in = getAssets().open(name);
            in.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }
}
