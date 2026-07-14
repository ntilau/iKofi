package com.ikofi.sshd.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import com.ikofi.sshd.R;
import com.ikofi.sshd.SshdSettings;

class UserPassStorage
        extends PreferenceDataStore {

    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_USERNAME = "sshd.authorized.username";
    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_PASSWORD = "sshd.authorized.password";

    private static final String ASTERIX = "********";

    @NonNull
    private final SettingsViewModel vm;

    @NonNull
    private final String notSetText;

    private boolean hasStoredPassword;
    @Nullable
    private String currentUsername;
    @Nullable
    private String currentPassword;

    private boolean passwordUpdated;

    UserPassStorage(@NonNull final Context context,
                    @NonNull final SettingsViewModel vm) {
        this.vm = vm;
        notSetText = context.getString(R.string.pref_not_set);

        final String[] up = SshdSettings.readPasswordFile(context);
        if (up != null) {
            hasStoredPassword = up[1] != null;

            currentUsername = up[0];
            currentPassword = null;
        }
    }

    /**
     * Called for initial population of the UI field.
     * With PreferenceDataStore this must be done manually.
     *
     * @return username
     */
    @Nullable
    String getCurrentUsername() {
        return currentUsername;
    }

    /**
     * Called for initial population of the UI field.
     * With PreferenceDataStore this must be done manually.
     *
     * @return password
     */
    @Nullable
    String getCurrentPassword() {
        return currentPassword;
    }

    /**
     * Updates are send by calling {@link SettingsViewModel#updatePasswordSummary(String)}.
     *
     * @return current summary text
     */
    @NonNull
    private String getPasswordSummaryText() {
        if (passwordUpdated && currentPassword != null && !currentPassword.isEmpty()) {
            return ASTERIX;
        }

        if (!passwordUpdated && hasStoredPassword) {
            return ASTERIX;
        }

        return notSetText;
    }

    /**
     * Write credentials to the dropbear file.
     *
     * @param context Current context
     */
    void storeCredentials(@NonNull final Context context)
            throws IOException, NoSuchAlgorithmException {
        SshdSettings.writePasswordFile(context, currentUsername, passwordUpdated, currentPassword);
    }

    @Override
    public void putString(@NonNull final String key,
                          @Nullable final String value) {
        switch (key) {
            case PK_SSHD_AUTH_USERNAME:
                // Don't check for empty and remove the password.
                // It's not user-friendly.
                // If the user is empty, the password is removed at save-time.
                currentUsername = value;
                break;
            case PK_SSHD_AUTH_PASSWORD:
                currentPassword = value;
                passwordUpdated = true;
                vm.updatePasswordSummary(getPasswordSummaryText());
                break;
            default:
                throw new IllegalArgumentException(key);
        }
    }

    @Nullable
    @Override
    public String getString(@NonNull final String key,
                            @Nullable final String defValue) {
        switch (key) {
            case PK_SSHD_AUTH_USERNAME:
                return currentUsername;
            case PK_SSHD_AUTH_PASSWORD:
                vm.updatePasswordSummary(getPasswordSummaryText());
                return currentPassword;
            default:
                throw new IllegalArgumentException(key);
        }
    }
}
