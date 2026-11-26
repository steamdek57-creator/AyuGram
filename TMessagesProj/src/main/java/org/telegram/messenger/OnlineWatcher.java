package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

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
            prefs().edit().putBoolean("last_online", online).apply();
            return false;
        }

        if (prefs().getBoolean("last_online", false) == online) {
            return false;
        }

        prefs().edit().putBoolean("last_online", online).apply();
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

                connection.getResponseCode();
                connection.disconnect();

            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
