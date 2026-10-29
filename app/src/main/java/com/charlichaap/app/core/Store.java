package com.charlichaap.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * State, validation and order logic for the on-phone order desk.
 *
 * This is a faithful port of the data rules in server.js so that the Android
 * app and the PC package behave the same way. No Android APIs are used here,
 * which keeps the whole thing testable on a plain JVM.
 */
public class Store {

    public static final String[] ORDER_STATUSES = {"pending", "preparing", "done", "cancelled"};
    public static final String[] ORDER_TYPES = {"dine-in", "takeaway", "delivery"};
    public static final String[] PAYMENT_METHODS = {"Cash at counter", "UPI at counter", "Cash on delivery"};

    private final File dataDir;
    private final File stateFile;
    private final File initialMenuFile;
    private JSONObject state;
    private String adminPassword;

    public Store(File dataDir, File initialMenuFile, String adminPassword) throws IOException {
        this.dataDir = dataDir;
        this.stateFile = new File(dataDir, "state.json");
        this.initialMenuFile = initialMenuFile;
        this.adminPassword = adminPassword == null ? "" : adminPassword;
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new IOException("Cannot create the data folder: " + dataDir);
        }
        this.state = loadState();
    }

    // ------------------------------------------------------------------ basics

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String password) {
        this.adminPassword = password == null ? "" : password;
    }

    public static String normalize(String value) {
        String v = value == null ? "" : value;
        v = Normalizer.normalize(v, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "");
        v = v.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9\\s]", " ");
        return v.replaceAll("\\s+", " ").trim();
    }

    public static String cleanText(String value, int max) {
        if (value == null) return "";
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static String cleanText(Object value, int max) {
        return cleanText(value == null || value == JSONObject.NULL ? "" : String.valueOf(value), max);
    }

    public static double roundMoney(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static String clockTime() {
        SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.ENGLISH);
        return fmt.format(new Date()).toLowerCase(Locale.ENGLISH);
    }

    private static boolean contains(String[] list, String value) {
        if (value == null) return false;
        for (String entry : list) {
            if (entry.equals(value)) return true;
        }
        return false;
    }

    private static boolean isObject(JSONObject value) {
        return value != null;
    }

    // ------------------------------------------------------------------ menu

    private static Double validPrice(Object raw, String field) throws ApiException {
        if (raw == null || raw == JSONObject.NULL || "".equals(raw)) return null;
        double n;
        try {
            n = Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Menu prices must be between \u20B90 and \u20B910,00,000.");
        }
        if (Double.isNaN(n) || Double.isInfinite(n) || n < 0 || n > 1000000) {
            throw new ApiException(400, "Menu prices must be between \u20B90 and \u20B910,00,000.");
        }
        return n;
    }

    private static JSONObject cleanMenuItem(JSONObject raw) throws ApiException {
        if (!isObject(raw)) throw new ApiException(400, "A menu item is invalid.");
        String name = cleanText(first(raw, "n", "name"), 80);
        String category = cleanText(first(raw, "c", "category"), 50);
        if (category.isEmpty()) category = "Other";
        if (name.isEmpty()) throw new ApiException(400, "Every menu item needs a name.");

        Double half = validPrice(raw.opt("h"), "h");
        Double full = validPrice(raw.opt("f"), "f");
        if (half == null && full == null) throw new ApiException(400, "Add at least one price for " + name + ".");

        String image = raw.optString("image", "");
        if (image.length() > 600000) {
            throw new ApiException(400, "The image for " + name + " is too large. Re-crop it before publishing.");
        }
        if (!image.isEmpty()
                && !image.matches("(?i)^https?://.*")
                && !image.matches("(?i)^data:image/(png|jpe?g|webp|gif);base64,.*")) {
            throw new ApiException(400, "The image for " + name + " must be an HTTPS URL or an image upload.");
        }

        List<String> aliases = new ArrayList<>();
        JSONArray rawAliases = raw.optJSONArray("a");
        if (rawAliases != null) {
            for (int i = 0; i < rawAliases.length() && aliases.size() < 40; i++) {
                String alias = cleanText(rawAliases.optString(i, ""), 100);
                if (!alias.isEmpty()) aliases.add(alias);
            }
        }
        boolean hasName = false;
        for (String alias : aliases) {
            if (normalize(alias).equals(normalize(name))) {
                hasName = true;
                break;
            }
        }
        if (!hasName) aliases.add(0, name);

        List<String> uniqueAliases = new ArrayList<>();
        Set<String> seenAlias = new HashSet<>();
        for (String alias : aliases) {
            if (seenAlias.add(alias)) uniqueAliases.add(alias);
        }

        JSONObject item = new JSONObject();
        JSONArray aliasArray = new JSONArray();
        for (String alias : uniqueAliases) aliasArray.put(alias);
        try {
            item.put("n", name);
            item.put("c", category);
            putNumber(item, "h", half);
            putNumber(item, "f", full);
            item.put("hLabel", cleanText(raw.optString("hLabel", ""), 24));
            item.put("fLabel", cleanText(raw.optString("fLabel", ""), 24));
            item.put("a", aliasArray);
            item.put("custom", raw.optBoolean("custom", false));
            item.put("available", raw.has("available") ? raw.optBoolean("available", true) : true);
            item.put("image", image);
        } catch (JSONException error) {
            throw new ApiException(400, "That menu item could not be read.");
        }
        return item;
    }

    private static void putNumber(JSONObject target, String key, Double value) throws ApiException {
        try {
            if (value == null) target.put(key, JSONObject.NULL);
            else target.put(key, value.doubleValue());
        } catch (JSONException error) {
            throw new ApiException(400, "That price could not be read.");
        }
    }

    private static Object first(JSONObject raw, String primary, String fallback) {
        Object value = raw.opt(primary);
        if (value == null || value == JSONObject.NULL || String.valueOf(value).isEmpty()) {
            value = raw.opt(fallback);
        }
        return value == null || value == JSONObject.NULL ? "" : value;
    }

    public static JSONArray cleanMenu(JSONArray items) throws ApiException {
        if (items == null || items.length() < 1 || items.length() > 600) {
            throw new ApiException(400, "The menu must contain between 1 and 600 items.");
        }
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject raw = items.optJSONObject(i);
            JSONObject item = cleanMenuItem(raw);
            String key = normalize(item.optString("n", ""));
            if (!seen.add(key)) throw new ApiException(400, "Duplicate menu item: " + item.optString("n") + ".");
            result.put(item);
        }
        return result;
    }

    public static JSONObject cleanConfig(JSONObject raw, JSONObject previous) throws ApiException {
        JSONObject config = new JSONObject();
        boolean open = previous == null || previous.optBoolean("isOpen", true);
        double tax = previous == null ? 0 : previous.optDouble("taxPercent", 0);
        double delivery = previous == null ? 0 : previous.optDouble("deliveryFee", 0);

        if (raw != null && raw.has("isOpen") && raw.opt("isOpen") != JSONObject.NULL) {
            open = raw.optBoolean("isOpen", true);
        }
        if (raw != null && raw.has("taxPercent") && raw.opt("taxPercent") != JSONObject.NULL) {
            double value = raw.optDouble("taxPercent", Double.NaN);
            if (Double.isNaN(value) || value < 0 || value > 100) {
                throw new ApiException(400, "Tax must be between 0 and 100 percent.");
            }
            tax = value;
        }
        if (raw != null && raw.has("deliveryFee") && raw.opt("deliveryFee") != JSONObject.NULL) {
            double value = raw.optDouble("deliveryFee", Double.NaN);
            if (Double.isNaN(value) || value < 0 || value > 100000) {
                throw new ApiException(400, "Delivery fee must be between \u20B90 and \u20B91,00,000.");
            }
            delivery = value;
        }
        try {
            config.put("isOpen", open);
            config.put("taxPercent", tax);
            config.put("deliveryFee", delivery);
        } catch (JSONException error) {
            throw new ApiException(400, "Those settings could not be read.");
        }
        return config;
    }

    // ------------------------------------------------------------------ state

    private JSONObject initialState() {
        JSONArray menu;
        try {
            menu = cleanMenu(new JSONArray(readFile(initialMenuFile)));
        } catch (Exception error) {
            menu = new JSONArray();
            JSONObject placeholder = new JSONObject();
            try {
                placeholder.put("n", "Menu setup required");
                placeholder.put("c", "Other");
                placeholder.put("h", 1);
                placeholder.put("f", JSONObject.NULL);
                JSONArray aliases = new JSONArray();
                aliases.put("Menu setup required");
                placeholder.put("a", aliases);
                placeholder.put("custom", true);
                placeholder.put("available", false);
                placeholder.put("image", "");
                placeholder.put("hLabel", "");
                placeholder.put("fLabel", "");
            } catch (JSONException ignored) {
            }
            menu.put(placeholder);
        }
        JSONObject fresh = new JSONObject();
        try {
            fresh.put("version", 1);
            fresh.put("orders", new JSONArray());
            fresh.put("menu", menu);
            fresh.put("config", cleanConfig(new JSONObject(), null));
            fresh.put("nextOrderNo", 1);
            fresh.put("updatedAt", isoNow());
        } catch (JSONException | ApiException ignored) {
        }
        return fresh;
    }

    private JSONObject loadState() {
        try {
            JSONObject saved = new JSONObject(readFile(stateFile));
            if (!saved.has("orders") || !(saved.opt("orders") instanceof JSONArray)) throw new IOException("bad state");
            if (!saved.has("menu") || !(saved.opt("menu") instanceof JSONArray)) throw new IOException("bad state");

            JSONObject fixed = new JSONObject();
            fixed.put("version", 1);
            fixed.put("menu", cleanMenu(saved.getJSONArray("menu")));
            fixed.put("config", cleanConfig(saved.optJSONObject("config"), null));

            JSONArray orders = new JSONArray();
            JSONArray rawOrders = saved.getJSONArray("orders");
            long largestId = 0;
            for (int i = 0; i < rawOrders.length(); i++) {
                JSONObject order = rawOrders.optJSONObject(i);
                if (order == null) continue;
                orders.put(order);
                largestId = Math.max(largestId, order.optLong("id", 0));
            }
            fixed.put("orders", orders);
            fixed.put("nextOrderNo", Math.max(saved.optLong("nextOrderNo", 1), largestId + 1));
            fixed.put("updatedAt", saved.optString("updatedAt", isoNow()));
            return fixed;
        } catch (Exception error) {
            JSONObject fresh = initialState();
            saveState(fresh);
            return fresh;
        }
    }

    public synchronized void saveState() {
        saveState(state);
    }

    private synchronized void saveState(JSONObject target) {
        try {
            target.put("updatedAt", isoNow());
            File tmp = new File(stateFile.getParentFile(), "state.json.tmp");
            byte[] bytes = target.toString(2).getBytes(StandardCharsets.UTF_8);
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (stateFile.exists() && !stateFile.delete()) {
                // fall through: rename may still succeed on some filesystems
            }
            if (!tmp.renameTo(stateFile)) {
                // Last resort: write in place.
                FileOutputStream direct = new FileOutputStream(stateFile);
                try {
                    direct.write(bytes);
                } finally {
                    direct.close();
                }
                tmp.delete();
            }
        } catch (Exception error) {
            // Nothing sensible to do here; the previous state file stays intact.
        }
    }

    private static String readFile(File file) throws IOException {
        if (!file.exists()) throw new IOException("Missing file: " + file);
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ accessors

    public synchronized JSONArray menu() {
        return state.optJSONArray("menu");
    }

    public synchronized JSONObject config() {
        return state.optJSONObject("config");
    }

    public synchronized JSONArray orders() {
        return state.optJSONArray("orders");
    }

    public synchronized String updatedAt() {
        return state.optString("updatedAt", "");
    }

    public synchronized void replaceMenu(JSONArray items) throws ApiException {
        try {
            state.put("menu", cleanMenu(items));
        } catch (JSONException e) {
            throw new ApiException(400, "The menu could not be saved.");
        }
        saveState();
    }

    public synchronized JSONObject replaceConfig(JSONObject raw) throws ApiException {
        JSONObject config = cleanConfig(raw, config());
        try {
            state.put("config", config);
        } catch (JSONException e) {
            throw new ApiException(400, "The settings could not be saved.");
        }
        saveState();
        return config;
    }

    public synchronized JSONObject findOrder(String id) {
        JSONArray orders = orders();
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.optJSONObject(i);
            if (order != null && id.equals(String.valueOf(order.opt("id")))) return order;
        }
        return null;
    }

    public synchronized boolean deleteOrder(String id) {
        JSONArray orders = orders();
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.optJSONObject(i);
            if (order != null && id.equals(String.valueOf(order.opt("id")))) {
                orders.remove(i);
                saveState();
                return true;
            }
        }
        return false;
    }

    private JSONObject findMenuItem(String name) {
        String key = normalize(name);
        JSONArray menu = menu();
        for (int i = 0; i < menu.length(); i++) {
            JSONObject item = menu.optJSONObject(i);
            if (item != null && normalize(item.optString("n", "")).equals(key)) return item;
        }
        return null;
    }

    // ------------------------------------------------------------------ orders

    private JSONArray prepareItems(JSONArray rawItems, boolean allowUnavailable) throws ApiException {
        if (rawItems == null || rawItems.length() < 1 || rawItems.length() > 100) {
            throw new ApiException(400, "An order must contain between 1 and 100 lines.");
        }
        JSONArray prepared = new JSONArray();
        for (int i = 0; i < rawItems.length(); i++) {
            JSONObject raw = rawItems.optJSONObject(i);
            String requestedName = raw == null ? "" : cleanText(raw.optString("name", ""), 80);
            JSONObject item = raw == null ? null : findMenuItem(raw.optString("name", ""));
            if (item == null) {
                throw new ApiException(400, "Menu item \u201C" + requestedName + "\u201D no longer exists.");
            }
            String itemName = item.optString("n", "");
            if (!item.optBoolean("available", true) && !allowUnavailable) {
                throw new ApiException(400, itemName + " is currently unavailable.");
            }
            String size = "f".equals(raw.optString("size", "")) ? "f"
                    : "h".equals(raw.optString("size", "")) ? "h" : "";
            if (size.isEmpty()) throw new ApiException(400, "Choose a valid size for " + itemName + ".");

            Object rateValue = item.opt(size);
            if (rateValue == null || rateValue == JSONObject.NULL) {
                throw new ApiException(400, "That size is not available for " + itemName + ".");
            }
            double rate;
            try {
                rate = Double.parseDouble(String.valueOf(rateValue));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "That size is not available for " + itemName + ".");
            }
            double qtyValue = raw.optDouble("qty", Double.NaN);
            if (Double.isNaN(qtyValue) || qtyValue != Math.floor(qtyValue) || qtyValue < 1 || qtyValue > 99) {
                throw new ApiException(400, "Quantity must be a whole number from 1 to 99.");
            }
            int qty = (int) qtyValue;

            String label = item.optString("f".equals(size) ? "fLabel" : "hLabel", "");
            if (label.isEmpty()) label = "f".equals(size) ? "Full" : "Half";

            JSONObject line = new JSONObject();
            try {
                line.put("name", itemName);
                line.put("qty", qty);
                line.put("size", size);
                line.put("sizeLabel", label);
                line.put("rate", rate);
            } catch (JSONException ignored) {
            }
            prepared.put(line);
        }
        return prepared;
    }

    private JSONObject getTotals(JSONArray items, String orderType, double taxPercent) {
        double subtotal = 0;
        int count = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject line = items.optJSONObject(i);
            if (line == null) continue;
            subtotal += line.optInt("qty", 0) * line.optDouble("rate", 0);
            count += line.optInt("qty", 0);
        }
        subtotal = roundMoney(subtotal);
        double taxAmount = roundMoney((double) Math.round(subtotal * taxPercent) / 100.0);
        double deliveryFee = "delivery".equals(orderType) ? roundMoney(config().optDouble("deliveryFee", 0)) : 0;
        JSONObject totals = new JSONObject();
        try {
            totals.put("subtotal", subtotal);
            totals.put("taxAmount", taxAmount);
            totals.put("taxPercent", taxPercent);
            totals.put("deliveryFee", deliveryFee);
            totals.put("total", roundMoney(subtotal + taxAmount + deliveryFee));
            totals.put("itemCount", count);
        } catch (JSONException ignored) {
        }
        return totals;
    }

    public synchronized JSONObject makeOrder(JSONObject payload, String source) throws ApiException {
        if ("customer".equals(source) && !config().optBoolean("isOpen", true)) {
            throw new ApiException(409, "The restaurant is not accepting orders right now.");
        }
        if (payload == null) throw new ApiException(400, "Order details are invalid.");

        String orderType = contains(ORDER_TYPES, payload.optString("orderType", ""))
                ? payload.optString("orderType", "") : "takeaway";
        String customerName = cleanText(payload.optString("customerName", ""), 80);
        String phone = cleanText(payload.optString("phone", ""), 20);
        String digits = phone.replaceAll("\\D", "");

        if ("customer".equals(source)) {
            if (customerName.length() < 2) throw new ApiException(400, "Enter the customer name.");
            if (digits.length() < 10 || digits.length() > 15) {
                throw new ApiException(400, "Enter a valid contact number with 10\u201315 digits.");
            }
            if ("dine-in".equals(orderType) && cleanText(payload.optString("tableNumber", ""), 20).isEmpty()) {
                throw new ApiException(400, "A table number is required for dine-in orders.");
            }
            if ("delivery".equals(orderType) && cleanText(payload.optString("address", ""), 400).isEmpty()) {
                throw new ApiException(400, "A delivery address is required.");
            }
        }

        JSONArray items = prepareItems(payload.optJSONArray("items"), "staff".equals(source));
        double taxPercent = config().optDouble("taxPercent", 0);
        JSONObject totals = getTotals(items, orderType, taxPercent);
        if (totals.optDouble("total", 0) <= 0) throw new ApiException(400, "The order total must be greater than zero.");

        String paymentMethod = contains(PAYMENT_METHODS, payload.optString("paymentMethod", ""))
                ? payload.optString("paymentMethod", "") : "Cash at counter";

        long id = Math.max(1, state.optLong("nextOrderNo", 1));
        JSONObject order = new JSONObject();
        try {
            order.put("id", id);
            order.put("items", items);
            order.put("subtotal", totals.optDouble("subtotal", 0));
            order.put("taxAmount", totals.optDouble("taxAmount", 0));
            order.put("taxPercent", totals.optDouble("taxPercent", 0));
            order.put("deliveryFee", totals.optDouble("deliveryFee", 0));
            order.put("total", totals.optDouble("total", 0));
            order.put("done", false);
            order.put("status", "pending");
            order.put("time", clockTime());
            order.put("createdAt", isoNow());
            order.put("source", source);
            order.put("customerName", customerName.isEmpty() && "staff".equals(source) ? "Walk-in" : customerName);
            order.put("phone", "customer".equals(source) ? phone : "");
            order.put("orderType", orderType);
            order.put("tableNumber", "dine-in".equals(orderType) ? cleanText(payload.optString("tableNumber", ""), 20) : "");
            order.put("address", "delivery".equals(orderType) ? cleanText(payload.optString("address", ""), 400) : "");
            order.put("notes", cleanText(payload.optString("notes", ""), 300));
            order.put("paymentMethod", paymentMethod);
            order.put("paymentStatus", "unpaid");

            state.put("nextOrderNo", id + 1);
            JSONArray orders = orders();
            JSONArray rebuilt = new JSONArray();
            rebuilt.put(order);
            for (int i = 0; i < orders.length(); i++) rebuilt.put(orders.get(i));
            state.put("orders", rebuilt);
        } catch (JSONException e) {
            throw new ApiException(400, "The order could not be saved.");
        }
        saveState();
        return order;
    }

    public synchronized JSONObject updateOrder(String id, JSONObject body) throws ApiException {
        JSONObject order = findOrder(id);
        if (order == null) throw new ApiException(404, "Order not found.");
        try {
            if (body.has("status")) {
                String status = body.optString("status", "");
                if (!contains(ORDER_STATUSES, status)) throw new ApiException(400, "Invalid order status.");
                order.put("status", status);
                order.put("done", "done".equals(status));
                if ("cancelled".equals(status)) order.put("cancelledAt", isoNow());
            }
            if (body.has("paymentStatus")) {
                String paymentStatus = body.optString("paymentStatus", "");
                if (!"paid".equals(paymentStatus) && !"unpaid".equals(paymentStatus)) {
                    throw new ApiException(400, "Invalid payment status.");
                }
                order.put("paymentStatus", paymentStatus);
                if ("paid".equals(paymentStatus)) order.put("paidAt", isoNow());
            }
            if (body.has("items")) {
                String status = order.optString("status", "pending");
                if ("done".equals(status) || "cancelled".equals(status)) {
                    throw new ApiException(409, "Completed or cancelled orders cannot be edited.");
                }
                JSONArray items = prepareItems(body.optJSONArray("items"), true);
                String orderType = order.optString("orderType", "takeaway");
                JSONObject totals = getTotals(items, orderType, order.optDouble("taxPercent", 0));
                order.put("items", items);
                order.put("subtotal", totals.optDouble("subtotal", 0));
                order.put("taxAmount", totals.optDouble("taxAmount", 0));
                double deliveryFee = "delivery".equals(orderType)
                        ? (order.has("deliveryFee") ? order.optDouble("deliveryFee", 0) : config().optDouble("deliveryFee", 0))
                        : 0;
                order.put("deliveryFee", deliveryFee);
                order.put("total", roundMoney(totals.optDouble("subtotal", 0)
                        + totals.optDouble("taxAmount", 0) + deliveryFee));
            }
            order.put("updatedAt", isoNow());
        } catch (JSONException e) {
            throw new ApiException(400, "The order could not be updated.");
        }
        saveState();
        return order;
    }

    // ------------------------------------------------------------------ backup

    public synchronized JSONObject backup() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("app", "Charlie Chaap Remote Backup");
            payload.put("version", 1);
            payload.put("exportedAt", isoNow());
            payload.put("data", state);
        } catch (JSONException ignored) {
        }
        return payload;
    }

    public synchronized JSONObject restore(JSONObject incoming) throws ApiException {
        if (incoming == null || incoming.opt("orders") == null || incoming.opt("menu") == null) {
            throw new ApiException(400, "Backup has an invalid structure.");
        }
        JSONArray rawOrders = incoming.optJSONArray("orders");
        JSONArray rawMenu = incoming.optJSONArray("menu");
        if (rawOrders == null || rawMenu == null) throw new ApiException(400, "Backup has an invalid structure.");
        if (rawOrders.length() > 50000) throw new ApiException(400, "Backup contains too many orders.");

        JSONArray menu = cleanMenu(rawMenu);
        List<JSONObject> validated = new ArrayList<>();
        for (int i = 0; i < rawOrders.length(); i++) {
            validated.add(validateRestoredOrder(rawOrders.optJSONObject(i)));
        }
        validated.sort((a, b) -> Long.compare(b.optLong("id", 0), a.optLong("id", 0)));

        JSONArray orders = new JSONArray();
        long largestId = 0;
        for (JSONObject order : validated) {
            orders.put(order);
            largestId = Math.max(largestId, order.optLong("id", 0));
        }
        JSONObject fresh = new JSONObject();
        try {
            fresh.put("version", 1);
            fresh.put("orders", orders);
            fresh.put("menu", menu);
            fresh.put("config", cleanConfig(incoming.optJSONObject("config"), null));
            fresh.put("nextOrderNo", Math.max(incoming.optLong("nextOrderNo", 1), largestId + 1));
            fresh.put("updatedAt", isoNow());
        } catch (JSONException e) {
            throw new ApiException(400, "Backup has an invalid structure.");
        }
        state = fresh;
        saveState();
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("count", orders.length());
            result.put("menuCount", menu.length());
        } catch (JSONException ignored) {
        }
        return result;
    }

    private static JSONObject validateRestoredOrder(JSONObject raw) throws ApiException {
        if (raw == null || !raw.has("id") || raw.optJSONArray("items") == null) {
            throw new ApiException(400, "Backup contains an invalid order.");
        }
        long id = raw.optLong("id", -1);
        if (id < 1) throw new ApiException(400, "Backup contains an invalid order number.");

        JSONArray rawItems;
        try {
            rawItems = raw.getJSONArray("items");
        } catch (JSONException error) {
            throw new ApiException(400, "Backup contains an invalid order.");
        }
        JSONArray items = new JSONArray();
        double subtotal = 0;
        for (int i = 0; i < rawItems.length(); i++) {
            JSONObject line = rawItems.optJSONObject(i);
            if (line == null) throw new ApiException(400, "Backup contains an invalid order line.");
            String name = cleanText(line.optString("name", ""), 80);
            double rate = line.optDouble("rate", Double.NaN);
            double qty = line.optDouble("qty", Double.NaN);
            if (name.isEmpty() || Double.isNaN(rate) || rate < 0 || Double.isNaN(qty)
                    || qty != Math.floor(qty) || qty < 1 || qty > 999) {
                throw new ApiException(400, "Backup contains an invalid order line.");
            }
            JSONObject fixed = new JSONObject();
            try {
                fixed.put("name", name);
                fixed.put("qty", (int) qty);
                fixed.put("rate", rate);
                fixed.put("size", "f".equals(line.optString("size", "")) ? "f" : "h");
                fixed.put("sizeLabel", cleanText(line.optString("sizeLabel", ""), 24));
            } catch (JSONException ignored) {
            }
            items.put(fixed);
            subtotal += qty * rate;
        }

        String status = contains(ORDER_STATUSES, raw.optString("status", "")) ? raw.optString("status", "") : "pending";
        String orderType = contains(ORDER_TYPES, raw.optString("orderType", "")) ? raw.optString("orderType", "") : "takeaway";
        subtotal = roundMoney(subtotal);
        double taxPercent = Math.max(0, Math.min(100, raw.optDouble("taxPercent", 0)));
        double taxAmount = roundMoney(raw.optDouble("taxAmount", 0));
        double deliveryFee = roundMoney(Math.max(0, raw.optDouble("deliveryFee", 0)));

        JSONObject order = new JSONObject();
        try {
            for (String key : new String[]{"id", "time", "customerName", "phone", "tableNumber", "address",
                    "notes", "paymentMethod", "source", "createdAt"}) {
                if (raw.has(key)) order.put(key, raw.opt(key));
            }
            order.put("id", id);
            order.put("items", items);
            order.put("status", status);
            order.put("done", "done".equals(status));
            order.put("orderType", orderType);
            order.put("subtotal", subtotal);
            order.put("taxPercent", taxPercent);
            order.put("taxAmount", taxAmount);
            order.put("deliveryFee", deliveryFee);
            order.put("total", roundMoney(subtotal + taxAmount + deliveryFee));
            order.put("customerName", cleanText(raw.optString("customerName", ""), 80));
            order.put("phone", cleanText(raw.optString("phone", ""), 20));
            order.put("tableNumber", cleanText(raw.optString("tableNumber", ""), 20));
            order.put("address", cleanText(raw.optString("address", ""), 400));
            order.put("notes", cleanText(raw.optString("notes", ""), 300));
            order.put("paymentStatus", "paid".equals(raw.optString("paymentStatus", "")) ? "paid" : "unpaid");
            order.put("createdAt", raw.has("createdAt") ? raw.optString("createdAt", isoNow()) : isoNow());
        } catch (JSONException ignored) {
        }
        return order;
    }
}
