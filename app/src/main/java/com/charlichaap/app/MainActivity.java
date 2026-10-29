package com.charlichaap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Home screen: run the order desk on this phone, or connect to one running
 * elsewhere on the same Wi-Fi.
 */
public class MainActivity extends Activity {

    private static final String PREFS = ServerService.PREFS;
    private static final String KEY_PASSWORD = ServerService.KEY_PASSWORD;

    private TextView statusText;
    private TextView urlText;
    private TextView hintText;
    private Button startButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        askForNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------------ UI

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#FFFAF4"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Charlie Chaap");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#2B1A10"));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Order desk for your restaurant");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.parseColor("#6B5646"));
        subtitle.setPadding(0, dp(2), 0, dp(18));
        root.addView(subtitle);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(Color.parseColor("#2B1A10"));
        root.addView(statusText);

        urlText = new TextView(this);
        urlText.setTextSize(18);
        urlText.setTypeface(Typeface.MONOSPACE);
        urlText.setTextColor(Color.parseColor("#C2410C"));
        urlText.setPadding(0, dp(6), 0, dp(6));
        urlText.setTextIsSelectable(true);
        root.addView(urlText);

        hintText = new TextView(this);
        hintText.setTextSize(13);
        hintText.setTextColor(Color.parseColor("#6B5646"));
        hintText.setPadding(0, 0, 0, dp(16));
        root.addView(hintText);

        startButton = primaryButton("Start order desk");
        startButton.setOnClickListener(v -> {
            if (ServerService.running) {
                stopService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
                toast("Order desk stopped");
                handler.postDelayed(this::refreshStatus, 600);
            } else {
                if (!hasPassword()) {
                    askPassword(this::startServer);
                } else {
                    startServer();
                }
            }
        });
        root.addView(startButton);

        root.addView(gap(10));

        LinearLayout shareRow = new LinearLayout(this);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);
        Button share = secondaryButton("Share address");
        share.setOnClickListener(v -> shareAddress());
        Button copy = secondaryButton("Copy");
        copy.setOnClickListener(v -> copyAddress());
        LinearLayout.LayoutParams halfParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shareRow.addView(share, halfParams);
        shareRow.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(shareRow);

        root.addView(gap(20));
        root.addView(sectionTitle("Open on this phone"));
        root.addView(actionButton("Customer menu", () -> openClient("/customer")));
        root.addView(actionButton("Staff dashboard", () -> openClient("/staff")));
        root.addView(actionButton("Owner controls", () -> openClient("/admin")));

        root.addView(gap(20));
        root.addView(sectionTitle("Settings"));
        root.addView(actionButton("Staff password", () -> askPassword(null)));
        root.addView(actionButton("Keep running in background", this::askBatteryExemption));
        root.addView(actionButton("Start automatically after reboot", this::toggleAutoStart));
        root.addView(actionButton("Connect to another order desk", this::askRemoteAddress));

        root.addView(gap(20));
        root.addView(sectionTitle("About"));
        root.addView(actionButton("Version and help", this::showAbout));

        setContentView(scroll);
    }

    private View gap(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.parseColor("#8A6F5C"));
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.parseColor("#C2410C"));
        button.setPadding(dp(12), dp(14), dp(12), dp(14));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.parseColor("#2B1A10"));
        button.setBackgroundColor(Color.parseColor("#F2E6D8"));
        return button;
    }

    private Button actionButton(String text, Runnable action) {
        Button button = secondaryButton(text);
        button.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(3), 0, dp(3));
        button.setLayoutParams(params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    // ------------------------------------------------------------------ status

    private void refreshStatus() {
        if (statusText == null) return;
        if (ServerService.running) {
            String url = customerUrl();
            statusText.setText("Order desk is running");
            statusText.setTextColor(Color.parseColor("#166534"));
            urlText.setText(url);
            urlText.setVisibility(View.VISIBLE);
            hintText.setText("Customers and staff join this phone's Wi-Fi or hotspot, then open this address. "
                    + "Keep the phone on charge.");
            startButton.setText("Stop order desk");
            startButton.setBackgroundColor(Color.parseColor("#7F1D1D"));
        } else {
            statusText.setText("Order desk is stopped");
            statusText.setTextColor(Color.parseColor("#7F1D1D"));
            urlText.setVisibility(View.GONE);
            String error = ServerService.lastError;
            hintText.setText(error != null && !error.isEmpty()
                    ? error
                    : "Tap Start to let phones on your Wi-Fi open the menu and place orders.");
            startButton.setText("Start order desk");
            startButton.setBackgroundColor(Color.parseColor("#C2410C"));
        }
    }

    private String customerUrl() {
        String ip = localAddress();
        return "http://" + (ip == null ? "this-phone" : ip) + ":" + ServerService.PORT + "/customer";
    }

    private String localAddress() {
        try {
            List<String> candidates = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.startsWith("169.254.")) continue;
                    candidates.add(host);
                }
            }
            for (String prefix : new String[]{"192.168.43.", "192.168.", "10.", "172."}) {
                for (String candidate : candidates) {
                    if (candidate.startsWith(prefix)) return candidate;
                }
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (Exception error) {
            return null;
        }
    }

    // ------------------------------------------------------------------ actions

    private void startServer() {
        Intent intent = new Intent(this, ServerService.class).setAction(ServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        toast("Starting the order desk...");
        handler.postDelayed(this::refreshStatus, 1500);
    }

    private void openClient(String path) {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        Intent intent = new Intent(this, ClientActivity.class);
        intent.putExtra(ClientActivity.EXTRA_URL, "http://127.0.0.1:" + ServerService.PORT + path);
        startActivity(intent);
    }

    private void askRemoteAddress() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.25:3000");

        new AlertDialog.Builder(this)
                .setTitle("Connect to another order desk")
                .setMessage("Use this on staff phones that should open an order desk running on a different device.")
                .setView(input)
                .setPositiveButton("Open", (dialog, which) -> {
                    String url = normaliseUrl(input.getText().toString());
                    if (url == null) {
                        toast("Enter a valid address");
                        return;
                    }
                    Intent intent = new Intent(this, ClientActivity.class);
                    intent.putExtra(ClientActivity.EXTRA_URL, url);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String normaliseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        Uri uri = Uri.parse(value);
        String path = uri.getPath();
        if (path == null || path.isEmpty()) value = value + "/customer";
        return value;
    }

    private boolean hasPassword() {
        String password = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASSWORD, "");
        return password != null && password.length() >= 12;
    }

    private void askPassword(Runnable onSaved) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("At least 12 characters");

        new AlertDialog.Builder(this)
                .setTitle("Staff password")
                .setMessage("Staff use this to open the dashboard and change the menu. "
                        + "Customers never see it. Write it down somewhere safe.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() < 12) {
                        toast("Use at least 12 characters");
                        return;
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PASSWORD, value).apply();
                    toast("Password saved");
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleAutoStart() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("auto_start", false);
        prefs.edit().putBoolean("auto_start", !enabled).apply();
        toast(enabled ? "Automatic start turned off" : "Order desk will start after a reboot");
    }

    private void askBatteryExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            toast("Open Settings > Battery > App battery usage and allow background activity");
        }
    }

    private void shareAddress() {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        String url = customerUrl();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, "Charlie Chaap menu: " + url);
        startActivity(Intent.createChooser(intent, "Share the menu address"));
    }

    private void copyAddress() {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Charlie Chaap", customerUrl()));
            toast("Address copied");
        }
    }

    private void showAbout() {
        String version = "1.0";
        long code = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("Charlie Chaap " + version)
                .setMessage("Build " + code + "\n\n"
                        + "This phone runs the whole order desk. Other phones on the same Wi-Fi or hotspot "
                        + "open the address shown on the home screen.\n\n"
                        + "Your menu, orders and settings stay on this phone. Install a newer APK over this "
                        + "one to update; nothing is lost.\n\n"
                        + "Tip: keep the phone on charge and allow background running.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 41);
            } catch (Exception ignored) {
            }
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
