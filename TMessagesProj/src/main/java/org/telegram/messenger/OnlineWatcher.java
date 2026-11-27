package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;
import org.json.JSONArray;
import org.json.JSONObject;

public class OnlineWatcher {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
            "onlinewatcher", Context.MODE_PRIVATE
        );
    }

    public static void setTarget(long userId) {
        prefs().edit()
            .putLong("target_user_id", userId)
            .remove("last_online")
            .apply();
    }


    public static void setBotSettings(String token, long chatId) throws Exception {
        String encryptedToken = OnlineWatcherCrypto.encrypt(token);
        prefs().edit()
            .putString("bot_token_encrypted", encryptedToken)
            .putLong("bot_chat_id", chatId)
            .apply();
    }

    public static String getBotToken() throws Exception {
        String encryptedToken = prefs().getString("bot_token_encrypted", null);
        if (encryptedToken == null) {
            return null;
        }
        return OnlineWatcherCrypto.decrypt(encryptedToken);
    }

    public static long getBotChatId() {
        return prefs().getLong("bot_chat_id", 0);
    }

    public static long getTarget() {
        return prefs().getLong("target_user_id", 0);
    }


    private static void addHistoryEvent(boolean online, long timestamp, long duration) {
        try {
            JSONArray history = new JSONArray(
                prefs().getString("activity_history", "[]")
            );

            JSONObject event = new JSONObject();
            event.put("online", online);
            event.put("timestamp", timestamp);
            event.put("duration", duration);
            history.put(event);

            while (history.length() > 1000) {
                history.remove(0);
            }

            prefs().edit()
                .putString("activity_history", history.toString())
                .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static long getCurrentSessionDuration() {
        long start = prefs().getLong("session_start", 0);
        if (start == 0 || !prefs().getBoolean("last_online", false)) {
            return 0;
        }
        return Math.max(0, System.currentTimeMillis() - start);
    }

    public static long getLongestSessionDuration() {
        return prefs().getLong("longest_session", 0);
    }


    public static String getActivityStats(long periodMillis) {
        try {
            JSONArray history = new JSONArray(getActivityHistory());
            long now = System.currentTimeMillis();
            long since = now - periodMillis;
            long onlineTime = 0;
            long sessions = 0;
            long totalCompleted = 0;

            for (int i = 0; i < history.length(); i++) {
                JSONObject event = history.getJSONObject(i);
                if (event.optBoolean("online", false)) {
                    long timestamp = event.optLong("timestamp", 0);
                    if (timestamp >= since && timestamp <= now) {
                        sessions++;
                    }
                } else {
                    long timestamp = event.optLong("timestamp", 0);
                    long duration = event.optLong("duration", 0);
                    if (timestamp >= since && timestamp <= now && duration > 0) {
                        onlineTime += duration;
                        totalCompleted++;
                    }
                }
            }

            if (prefs().getBoolean("last_online", false)) {
                long start = prefs().getLong("session_start", 0);
                if (start >= since && start > 0) {
                    onlineTime += Math.max(0, now - start);
                }
            }

            long average = totalCompleted > 0
                ? onlineTime / totalCompleted : 0;

            JSONObject result = new JSONObject();
            result.put("sessions", sessions);
            result.put("online_time", onlineTime);
            result.put("average_session", average);
            return result.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return "{}";
        }
    }

    public static String getDailyStats() {
        return getActivityStats(24L * 60 * 60 * 1000);
    }

    public static String getWeeklyStats() {
        return getActivityStats(7L * 24 * 60 * 60 * 1000);
    }


    private static String formatDuration(long millis) {
        long minutes = Math.max(0, millis / 60000);
        long hours = minutes / 60;
        minutes %= 60;
        long days = hours / 24;
        hours %= 24;
        if (days > 0) {
            return days + " д. " + hours + " ч. " + minutes + " мин.";
        }
        return hours + " ч. " + minutes + " мин.";
    }

    public static String getDashboardText() {
        try {
            JSONObject daily = new JSONObject(getDailyStats());
            JSONObject weekly = new JSONObject(getWeeklyStats());

            StringBuilder result = new StringBuilder();
            result.append("ТЕКУЩАЯ СЕССИЯ\n");
            long current = getCurrentSessionDuration();
            result.append(current > 0 ? formatDuration(current) : "Сейчас не в сети");

            result.append("\n\nРЕКОРД ОНЛАЙН\n");
            result.append(formatDuration(getLongestSessionDuration()));

            result.append("\n\nЗА ПОСЛЕДНИЕ 24 ЧАСА\n");
            result.append("Сессий: ").append(daily.optLong("sessions", 0));
            result.append("\nОнлайн: ").append(formatDuration(daily.optLong("online_time", 0)));
            result.append("\nСредняя сессия: ").append(formatDuration(daily.optLong("average_session", 0)));

            result.append("\n\nЗА 7 ДНЕЙ\n");
            result.append("Сессий: ").append(weekly.optLong("sessions", 0));
            result.append("\nОнлайн: ").append(formatDuration(weekly.optLong("online_time", 0)));
            result.append("\nСредняя сессия: ").append(formatDuration(weekly.optLong("average_session", 0)));

            return result.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return "Не удалось загрузить статистику.";
        }
    }

    public static String getActivityHistory() {
        return prefs().getString("activity_history", "[]");
    }

    public static boolean updateStatus(long userId, TLRPC.UserStatus status) {
        if (userId == 0 || userId != getTarget() || status == null) {
            return false;
        }

        final boolean online;

        if (status instanceof TLRPC.TL_userStatusOnline) {
            online = true;
        } else if (status instanceof TLRPC.TL_userStatusOffline) {
            online = false;
        } else {
            return false;
        }

        boolean known = prefs().contains("last_online");

        if (!known) {
            long now = System.currentTimeMillis();
            prefs().edit().putBoolean("last_online", online).apply();
            if (online) {
                prefs().edit().putLong("session_start", now).apply();
                addHistoryEvent(true, now, 0);
            }
            return false;
        }

        if (prefs().getBoolean("last_online", false) == online) {
            return false;
        }

        long now = System.currentTimeMillis();
        prefs().edit().putBoolean("last_online", online).apply();

        if (online) {
            prefs().edit().putLong("session_start", now).apply();
            addHistoryEvent(true, now, 0);
        } else {
            long start = prefs().getLong("session_start", 0);
            long duration = start > 0 ? Math.max(0, now - start) : 0;
            addHistoryEvent(false, now, duration);

            if (duration > getLongestSessionDuration()) {
                prefs().edit().putLong("longest_session", duration).apply();
            }

            prefs().edit().remove("session_start").apply();
        }
        return true;
    }
    public static void sendStatusNotification(boolean online) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String token = getBotToken();
                long chatId = getBotChatId();

                if (token == null || token.isEmpty() || chatId == 0) {
                    return;
                }

                String message = online
                    ? "🟢 Отслеживаемый пользователь в сети"
                    : "⚫ Отслеживаемый пользователь вышел из сети";

                java.net.URL url = new java.net.URL(
                    "https://api.telegram.org/bot" + token + "/sendMessage"
                );

                java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) url.openConnection();

                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                );

                String data = "chat_id=" + chatId
                    + "&text=" + java.net.URLEncoder.encode(
                        message, "UTF-8"
                    );

                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                int responseCode = connection.getResponseCode();
                java.io.InputStream responseStream =
                    responseCode >= 200 && responseCode < 400
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                StringBuilder responseBody = new StringBuilder();
                if (responseStream != null) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                responseStream,
                                java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBody.append(line);
                        }
                    }
                }

                if (responseCode != 200 ||
                        !responseBody.toString().contains("\\"ok\\":true")) {
                    FileLog.e(new Exception(
                        "OnlineWatcher Telegram API error HTTP "
                        + responseCode + ": " + responseBody
                    ));
                }
                connection.disconnect();

            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
