package com.nssf.datacapture;

import android.content.Context;
import android.provider.Settings;

import androidx.room.Room;

import net.sqlcipher.database.SupportFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * DatabaseProvider
 * 
 * Singleton provider for SQLCipher-encrypted Room database.
 */
public class DatabaseProvider {

    private static final String DB_NAME = "nssf_members_encrypted.db";
    private static final int KEY_LENGTH = 32;

    private static AppDatabase instance;

    /**
     * Get singleton database instance.
     * 
     * @param context Application context
     * @param userPin User-entered PIN (collected at first launch, min 4 digits)
     * @return SQLCipher-encrypted Room database
     */
    public static synchronized AppDatabase getInstance(Context context, String userPin) {
        if (instance == null) {
            String passphrase = derivePassphrase(context, userPin);

            System.loadLibrary("sqlcipher");

            SupportFactory factory = new SupportFactory(passphrase.getBytes(StandardCharsets.UTF_8));

            instance = Room.databaseBuilder(
                    context.getApplicationContext(),
                    AppDatabase.class,
                    DB_NAME
                )
                .openHelperFactory(factory)
                .build();
        }
        return instance;
    }

    /**
     * Get instance with cached PIN or default initialization.
     */
    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            return getInstance(context, "0000");
        }
        return instance;
    }

    /**
     * Derive SQLCipher passphrase from device-bound data + user PIN.
     * 
     * Formula: SHA-256(AndroidID + Build.SERIAL + userPIN).substring(0, 32)
     */
    private static String derivePassphrase(Context context, String userPin) {
        String androidId = Settings.Secure.getString(
            context.getContentResolver(), 
            Settings.Secure.ANDROID_ID
        );
        if (androidId == null) androidId = "unknown_device";

        String serial = android.os.Build.SERIAL;
        if (serial == null || serial.equalsIgnoreCase("unknown")) {
            serial = android.os.Build.ID;
        }

        String combined = androidId + serial + (userPin != null ? userPin : "0000");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().substring(0, KEY_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            return (combined + "00000000000000000000000000000000").substring(0, KEY_LENGTH);
        }
    }

    /**
     * Auto-purge records older than retentionDays.
     */
    public static void purgeOldRecords(Context context, int retentionDays) {
        if (instance == null) return;
        long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000);
        instance.memberRecordDao().purgeOldRecords(cutoff);
    }

    /**
     * Close database and clear instance. Call on app sign-out or teardown.
     */
    public static synchronized void closeDatabase() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
}
