package org.telegram.messenger;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class OnlineWatcherCrypto {

    private static final String ALIAS = "onlinewatcher_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (keyStore.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry)
                keyStore.getEntry(ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );

        generator.init(new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        );

        return generator.generateKey();
    }

    public static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getKey());

        byte[] encrypted = cipher.doFinal(
            value.getBytes(StandardCharsets.UTF_8)
        );
        byte[] iv = cipher.getIV();
        byte[] result = new byte[iv.length + encrypted.length];

        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);

        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    public static String decrypt(String value) throws Exception {
        byte[] data = Base64.decode(value, Base64.NO_WRAP);

        if (data.length < 29) {
            throw new IllegalArgumentException("Invalid encrypted data");
        }

        byte[] iv = new byte[12];
        byte[] encrypted = new byte[data.length - iv.length];

        System.arraycopy(data, 0, iv, 0, iv.length);
        System.arraycopy(data, iv.length, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(),
            new GCMParameterSpec(128, iv)
        );

        return new String(
            cipher.doFinal(encrypted),
            StandardCharsets.UTF_8
        );
    }
}
