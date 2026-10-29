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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Home screen: the "Restaurant setup" panel (orders switch, customer menu, staff
 * access, backup) with the order-desk server controls directly underneath.
 */
public class MainActivity extends Activity {

    private static final String PREFS = ServerService.PREFS;
    private static final String KEY_PASSWORD = ServerService.KEY_PASSWORD;

    private static final int REQ_EXPORT = 71;
    private static final int REQ_RESTORE = 72;

    private static final int INK = Color.parseColor("#111114");
    private static final int MUTED = Color.parseColor("#6E6E78");
    private static final int PAGE = Color.parseColor("#F2F2F2");
    private static final int GREEN = Color.parseColor("#1B7F4B");
    private static final int GREEN_SOFT = Color.parseColor("#E7F4EA");
    private static final int GOLD = Color.parseColor("#F5C97D");
    private static final int CREAM = Color.parseColor("#FDF0DC");
    private static final int LILAC = Color.parseColor("#EFEFF5");
    private static final int ORANGE = Color.parseColor("#C2410C");
    private static final int LINE = Color.parseColor("#EDEDF0");

    static {
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private LinearLayout navBar;
    private final List<View> navItems = new ArrayList<>();

    private Switch ordersSwitch;
    private TextView ordersPill;
    private View ordersDot;
    private TextView ordersStatusText;

    // ------------------------------------------------------------------ shell

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildShell();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildHome());
        setContentView(root);
    }

    // ------------------------------------------------------------------ home

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(20));
        scroll.addView(root);

        root.addView(buildSetupCard());
        root.addView(space(14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        LinearLayout left = buildTile(R.drawable.ic_shop, "Customer menu", "Open menu for customers");
        left.setOnClickListener(v -> openWeb("/customer", null));
        LinearLayout right = buildTile(R.drawable.ic_link, "Copy customer link", "Share your menu link");
        right.setOnClickListener(v -> copyAddress());
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftParams.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightParams.setMargins(dp(6), 0, 0, 0);
        row.addView(left, leftParams);
        row.addView(right, rightParams);
        root.addView(row);
        root.addView(space(14));

        root.addView(buildUpdateMenuCard());
        root.addView(space(14));
        root.addView(buildStaffCard());
        root.addView(space(14));
        root.addView(buildBackupCard());

        root.addView(space(20));

        TextView deskHeading = new TextView(this);
        deskHeading.setText("Order desk");
        deskHeading.setTextSize(15);
        deskHeading.setTypeface(Typeface.DEFAULT_BOLD);
        deskHeading.setTextColor(Color.parseColor("#8A8A94"));
        deskHeading.setPadding(dp(4), 0, 0, dp(10));
        root.addView(deskHeading);

        root.addView(buildServerSection());

        refreshOrdersState();
        return scroll;
    }

    private View buildSetupCard() {
        LinearLayout card = card();
        card.setPadding(dp(20), dp(20), dp(20), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Restaurant setup");
        title.setTextSize(24);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        title.setSingleLine(true);
        if (Build.VERSION.SDK_INT >= 26) {
            title.setAutoSizeTextTypeUniformWithConfiguration(18, 26, 1, TypedValue.COMPLEX_UNIT_SP);
        }
        titles.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Manage your menu and staff access");
        sub.setTextSize(14);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(4), 0, 0);
        titles.addView(sub);

        header.addView(titles);

        TextView avatar = new TextView(this);
        avatar.setText("CC");
        avatar.setTextSize(20);
        avatar.setTextColor(Color.WHITE);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(roundRect(INK, 0, 0, 20, 20));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(66), dp(66));
        avatarParams.setMargins(dp(12), 0, 0, 0);
        header.addView(avatar, avatarParams);

        card.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(16), 0, dp(2));
        card.addView(divider, dividerParams);

        LinearLayout ordersRow = new LinearLayout(this);
        ordersRow.setOrientation(LinearLayout.HORIZONTAL);
        ordersRow.setGravity(Gravity.CENTER_VERTICAL);
        ordersRow.setPadding(0, dp(14), 0, 0);

        ordersDot = new View(this);
        ordersDot.setBackground(circle(GREEN));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMargins(0, dp(2), dp(12), 0);
        ordersRow.addView(ordersDot, dotParams);

        LinearLayout ordersTexts = new LinearLayout(this);
        ordersTexts.setOrientation(LinearLayout.VERTICAL);
        ordersTexts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ordersTitle = new TextView(this);
        ordersTitle.setText("Orders");
        ordersTitle.setTextSize(18);
        ordersTitle.setTextColor(INK);
        ordersTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ordersTexts.addView(ordersTitle);

        ordersStatusText = new TextView(this);
        ordersStatusText.setText("Customers can place orders");
        ordersStatusText.setTextSize(12.5f);
        ordersStatusText.setTextColor(MUTED);
        ordersTexts.addView(ordersStatusText);
        ordersRow.addView(ordersTexts);

        ordersPill = new TextView(this);
        ordersPill.setText("Open");
        ordersPill.setTextSize(14);
        ordersPill.setTypeface(Typeface.DEFAULT_BOLD);
        ordersPill.setGravity(Gravity.CENTER);
        ordersPill.setPadding(dp(14), dp(9), dp(14), dp(9));
        ordersPill.setBackground(roundRect(GREEN_SOFT, 0, 0, 12, 12));
        ordersPill.setTextColor(GREEN);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.setMargins(0, 0, dp(10), 0);
        ordersRow.addView(ordersPill, pillParams);

        ordersSwitch = new Switch(this);
        ordersSwitch.setChecked(true);
        tintSwitch(ordersSwitch);
        ordersSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            applyOrdersState(isChecked, true);
            saveOrdersState(isChecked);
        });
        ordersRow.addView(ordersSwitch);

        card.addView(ordersRow);
        return card;
    }

    private void tintSwitch(Switch view) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                view.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                view.setTrackTintList(android.content.res.ColorStateList.valueOf(
                        view.isChecked() ? GREEN : Color.parseColor("#C9C9D0")));
            }
        } catch (Exception ignored) {
        }
    }

    private LinearLayout buildTile(int iconRes, String title, String subtitle) {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(16), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(INK);
        icon.setPadding(dp(11), dp(11), dp(11), dp(11));
        icon.setBackground(roundRect(CREAM, 0, 0, 15, 15));
        top.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(Color.parseColor("#B4B4BE"));
        top.addView(chevron, new LinearLayout.LayoutParams(dp(17), dp(17)));

        card.addView(top);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(15);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(12), 0, 0);
        card.addView(heading);

        TextView detail = new TextView(this);
        detail.setText(subtitle);
        detail.setTextSize(11.5f);
        detail.setTextColor(MUTED);
        detail.setPadding(0, dp(2), 0, 0);
        card.addView(detail);

        return card;
    }

    private View buildUpdateMenuCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(GOLD, 0, 0, 20, 20));
        card.setElevation(dp(1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_edit);
        icon.setColorFilter(INK);
        card.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(16), 0, 0, 0);
        texts.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText("Update menu");
        title.setTextSize(17);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Add, edit or remove menu items");
        sub.setTextSize(11.5f);
        sub.setTextColor(Color.parseColor("#6B5320"));
        texts.addView(sub);
        card.addView(texts);

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        chevron.setColorFilter(Color.parseColor("#6B5320"));
        card.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

        card.setOnClickListener(v -> openWeb("/admin", "menu"));
        return card;
    }

    private View buildStaffCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_people);
        icon.setColorFilter(INK);
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        icon.setBackground(circle(LILAC));
        card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(13), 0, dp(8), 0);
        texts.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText("Staff access");
        title.setTextSize(16.5f);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Allow staff to manage orders");
        sub.setTextSize(11.5f);
        sub.setTextColor(MUTED);
        texts.addView(sub);
        card.addView(texts);

        TextView button = new TextView(this);
        button.setText("Enter as staff");
        button.setTextSize(13);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(13), dp(12), dp(13), dp(12));
        button.setBackground(roundRect(INK, 0, 0, 13, 13));
        button.setOnClickListener(v -> openWeb("/staff", "orders"));
        card.addView(button);

        return card;
    }

    private View buildBackupCard() {
        boolean narrow = getResources().getConfiguration().screenWidthDp < 400;
        LinearLayout card = card();
        card.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_cloud);
        icon.setColorFilter(INK);
        icon.setPadding(dp(13), dp(13), dp(13), dp(13));
        icon.setBackground(circle(LILAC));
        card.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMargins(dp(14), 0, dp(8), 0);
        texts.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText("Backup");
        title.setTextSize(16.5f);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Keep your data safe");
        sub.setTextSize(11.5f);
        sub.setTextColor(MUTED);
        texts.addView(sub);
        card.addView(texts);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        if (narrow) {
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, dp(12), 0, 0);
            buttonRow.setLayoutParams(rowParams);
            buttonRow.setGravity(Gravity.END);
        }
        buttonRow.addView(outlineButton("Export", R.drawable.ic_upload, this::exportBackup));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(8), dp(1));
        buttonRow.addView(new View(this), gap);
        buttonRow.addView(outlineButton("Restore", R.drawable.ic_download, this::importBackup));
        card.addView(buttonRow);

        return card;
    }

    private LinearLayout outlineButton(String label, int iconRes, Runnable action) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(11), dp(14), dp(11));
        GradientDrawable bg = roundRect(Color.WHITE, 0, 0, 13, 13);
        bg.setStroke(dp(1), Color.parseColor("#E2E2E8"));
        button.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(INK);
        button.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13);
        text.setTextColor(INK);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setPadding(dp(6), 0, 0, 0);
        button.addView(text);

        button.setOnClickListener(v -> action.run());
        return button;
    }

    // ------------------------------------------------------------------ more

    private LinearLayout buildServerSection() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout status = card();
        status.setPadding(dp(20), dp(18), dp(20), dp(18));

        TextView heading = new TextView(this);
        heading.setText("Order desk server");
        heading.setTextSize(20);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        status.addView(heading);

        TextView state = new TextView(this);
        state.setText(ServerService.running ? "Running" : "Stopped");
        state.setTextSize(13.5f);
        state.setTextColor(ServerService.running ? GREEN : Color.parseColor("#9A2A2A"));
        state.setPadding(0, dp(3), 0, 0);
        status.addView(state);

        TextView address = new TextView(this);
        address.setText(ServerService.running ? customerUrl() : "Start the desk to see the address");
        address.setTextSize(13.5f);
        address.setTypeface(Typeface.MONOSPACE);
        address.setTextColor(ServerService.running ? ORANGE : MUTED);
        address.setPadding(0, dp(8), 0, dp(4));
        address.setTextIsSelectable(true);
        status.addView(address);

        TextView note = new TextView(this);
        String error = ServerService.lastError;
        note.setText(error != null && !error.isEmpty() ? error
                : "Phones on the same Wi-Fi or hotspot open that address.");
        note.setTextSize(12);
        note.setTextColor(MUTED);
        note.setPadding(0, dp(2), 0, dp(14));
        status.addView(note);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView start = pillButton(ServerService.running ? "Stop order desk" : "Start order desk",
                ServerService.running ? Color.parseColor("#7F1D1D") : ORANGE, Color.WHITE);
        start.setOnClickListener(v -> toggleServer());
        actions.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView share = pillButton("Share address", Color.parseColor("#F0F0F3"), INK);
        share.setOnClickListener(v -> shareAddress());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shareParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(share, shareParams);
        status.addView(actions);
        root.addView(status);

        root.addView(space(14));
        root.addView(listCard(new String[][]{
                {"Staff password", "Change the password staff sign in with"},
                {"Keep running in background", "Stop Android from pausing the desk"},
                {"Start automatically after reboot", "Bring the desk back after a restart"},
                {"Connect to another order desk", "Open a desk running on a different phone"},
                {"Version and help", "Build number and quick tips"}
        }));

        return root;
    }

    private View listCard(String[][] rows) {
        LinearLayout card = card();
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (int i = 0; i < rows.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(12), dp(14));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(this);
            title.setText(rows[i][0]);
            title.setTextSize(15);
            title.setTextColor(INK);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(title);

            TextView sub = new TextView(this);
            sub.setText(rows[i][1]);
            sub.setTextSize(12);
            sub.setTextColor(MUTED);
            texts.addView(sub);
            row.addView(texts);

            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron);
            chevron.setColorFilter(Color.parseColor("#B4B4BE"));
            row.addView(chevron, new LinearLayout.LayoutParams(dp(16), dp(16)));

            row.setOnClickListener(v -> onMoreRow(index));
            card.addView(row);
        }
        return card;
    }

    private void onMoreRow(int index) {
        switch (index) {
            case 0: askPassword(null); break;
            case 1: askBatteryExemption(); break;
            case 2: toggleAutoStart(); break;
            case 3: askRemoteAddress(); break;
            default: showAbout(); break;
        }
    }

    private TextView pillButton(String label, int background, int textColor) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(13), dp(14), dp(13));
        button.setBackground(roundRect(background, 0, 0, 13, 13));
        return button;
    }

    // ------------------------------------------------------------------ helpers

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private View space(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(Color.WHITE, 0, 0, 20, 20));
        card.setElevation(dp(1));
        return card;
    }

    private GradientDrawable roundRect(int color, int tl, int tr, int br, int bl) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl)});
        return drawable;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    // ------------------------------------------------------------------ orders switch

    private void refreshOrdersState() {
        new Thread(() -> {
            try {
                JSONObject response = http("GET", "/api/public-config", null);
                JSONObject config = response.optJSONObject("config");
                final boolean open = config == null || config.optBoolean("isOpen", true);
                handler.post(() -> applyOrdersState(open, false));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void applyOrdersState(boolean open, boolean animate) {
        if (ordersSwitch != null) {
            ordersSwitch.setOnCheckedChangeListener(null);
            ordersSwitch.setChecked(open);
            tintSwitch(ordersSwitch);
            ordersSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                applyOrdersState(isChecked, true);
                saveOrdersState(isChecked);
            });
        }
        if (ordersPill != null) {
            ordersPill.setText(open ? "Open" : "Closed");
            ordersPill.setTextColor(open ? GREEN : Color.parseColor("#9A2A2A"));
            ordersPill.setBackground(roundRect(open ? GREEN_SOFT : Color.parseColor("#FBE9E9"), 0, 0, 12, 12));
        }
        if (ordersDot != null) {
            ordersDot.setBackground(circle(open ? GREEN : Color.parseColor("#C9C9D0")));
        }
        if (ordersStatusText != null) {
            ordersStatusText.setText(open ? "Customers can place orders" : "Orders are paused");
        }
    }

    private void saveOrdersState(final boolean open) {
        new Thread(() -> {
            try {
                ensureStaffSession();
                JSONObject body = new JSONObject();
                body.put("isOpen", open);
                http("PUT", "/api/service-config", body.toString());
                handler.post(() -> toast(open ? "Orders are open" : "Orders paused"));
            } catch (Exception error) {
                handler.post(() -> {
                    toast(error.getMessage() == null ? "Could not change the order status" : error.getMessage());
                    refreshOrdersState();
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ http

    private void ensureStaffSession() throws Exception {
        JSONObject session = http("GET", "/api/auth/session", null);
        if (session.optBoolean("authenticated", false)) return;
        String password = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PASSWORD, "");
        if (password == null || password.length() < 12) {
            throw new Exception("Set the staff password in More first");
        }
        JSONObject body = new JSONObject();
        body.put("password", password);
        http("POST", "/api/auth/login", body.toString());
    }

    private JSONObject http(String method, String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + ServerService.PORT + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readAll(stream);
        connection.disconnect();
        JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (code >= 400) throw new Exception(json.optString("error", "Request failed"));
        return json;
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ backup

    private void exportBackup() {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "charlie-chaap-backup.json");
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception error) {
            toast("No file picker available");
        }
    }

    private void importBackup() {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_RESTORE);
        } catch (Exception error) {
            toast("No file picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            new Thread(() -> {
                try {
                    ensureStaffSession();
                    JSONObject backup = http("GET", "/api/backup", null);
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new Exception("Could not open the file");
                    try {
                        out.write(backup.toString(2).getBytes(StandardCharsets.UTF_8));
                    } finally {
                        out.close();
                    }
                    handler.post(() -> toast("Backup saved"));
                } catch (Exception error) {
                    handler.post(() -> toast("Backup failed: " + error.getMessage()));
                }
            }).start();
        } else if (requestCode == REQ_RESTORE) {
            new Thread(() -> {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    String text = readAll(in);
                    JSONObject parsed = new JSONObject(text);
                    JSONObject payload = new JSONObject();
                    payload.put("data", parsed.has("data") ? parsed.getJSONObject("data") : parsed);
                    ensureStaffSession();
                    JSONObject result = http("POST", "/api/backup/restore", payload.toString());
                    final int orders = result.optInt("count", 0);
                    handler.post(() -> toast("Restored " + orders + " orders"));
                } catch (Exception error) {
                    handler.post(() -> toast("Restore failed: " + error.getMessage()));
                }
            }).start();
        }
    }

    // ------------------------------------------------------------------ misc actions

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

    private void copyAddress() {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Charlie Chaap", customerUrl()));
            toast("Customer link copied");
        }
    }

    private void shareAddress() {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, "Charlie Chaap menu: " + customerUrl());
        startActivity(Intent.createChooser(intent, "Share the menu address"));
    }

    private void openWeb(String path, String tabName) {
        if (!ServerService.running) {
            toast("Start the order desk first");
            return;
        }
        Intent intent = new Intent(this, ClientActivity.class);
        intent.putExtra(ClientActivity.EXTRA_URL, "http://127.0.0.1:" + ServerService.PORT + path);
        if (tabName != null) intent.putExtra(ClientActivity.EXTRA_TAB, tabName);
        startActivity(intent);
    }

    private void toggleServer() {
        if (ServerService.running) {
            startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
            toast("Order desk stopped");
        } else {
            if (!hasPassword()) {
                askPassword(this::startServer);
            } else {
                startServer();
            }
        }
        handler.postDelayed(() -> {
            content.removeAllViews();
            content.addView(buildHome());
        }, 900);
    }

    private void startServer() {
        Intent intent = new Intent(this, ServerService.class).setAction(ServerService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        toast("Starting the order desk...");
        handler.postDelayed(this::refreshOrdersState, 1500);
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
                .setMessage("Staff use this to open the dashboard and change the menu. Customers never see it.")
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

    private void askBatteryExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            toast("Open Settings > Battery > App battery usage and allow background activity");
        }
    }

    private void toggleAutoStart() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("auto_start", false);
        prefs.edit().putBoolean("auto_start", !enabled).apply();
        toast(enabled ? "Automatic start turned off" : "Order desk will start after a reboot");
    }

    private void askRemoteAddress() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.25:3000");

        new AlertDialog.Builder(this)
                .setTitle("Connect to another order desk")
                .setMessage("For staff phones that should open a desk running on a different device.")
                .setView(input)
                .setPositiveButton("Open", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
                    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
                    Uri uri = Uri.parse(value);
                    String path = uri.getPath();
                    if (path == null || path.isEmpty()) value = value + "/customer";
                    Intent intent = new Intent(this, ClientActivity.class);
                    intent.putExtra(ClientActivity.EXTRA_URL, value);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
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
                        + "open the customer link.\n\n"
                        + "Your menu, orders and settings stay on this phone. Install a newer APK over this "
                        + "one to update; nothing is lost.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
