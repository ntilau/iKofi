package com.hardbacknutter.sshd;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Configuration file management for the Dropbear SSH daemon.
 *
 * Manages the hidden configuration directory ({@code files/.dropbear/}) which stores:
 * <ul>
 *   <li>{@code master_password} — hashed user/password file (SHA-512, base64)</li>
 *   <li>{@code authorized_keys} — public keys for key-based auth</li>
 *   <li>{@code dropbear_*_host_key} — server host keys (auto-generated)</li>
 *   <li>{@code dropbear.err} — native process log (polled by ServiceViewModel)</li>
 *   <li>{@code dropbear.pid} — PID file for crash recovery</li>
 * </ul>
 *
 * <h2>Password file write logic</h2>
 * <pre>
 *  username  pwUpdated  password    file exists → action
 *  ────────  ─────────  ────────    ──────────────────
 *  null/blank —          —          delete file (if exists)
 *  set       true       null/blank  delete file (if exists)
 *  set       false      —           absent → no-op
 *  set       false      —           present → update username, keep old hash
 *  set       true       set         — → write user + new hash
 * </pre>
 *
 * The file names are kept in sync with:
 * <ul>
 *     <li>"cpp/jni-dropbear.h"</li>
 *     <li>"res/xml/backup_rules.xml"</li>
 *     <li>"res/xml/data_extraction_rules.xml"</li>
 * </ul>
 */
@SuppressWarnings({
        "BlockingMethodInNonBlockingContext",
        "ResultOfMethodCallIgnored",
        "ImplicitDefaultCharsetUsage"})
public final class SshdSettings {

    /**
     * File with the fixed user/password.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     * <p>
     * I should have named this "smurf_password" ...
     */
    public static final String AUTHORIZED_USERS = "master_password";
    /**
     * The traditional OpenSSH key file.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     */
    static final String AUTHORIZED_KEYS = "authorized_keys";

    /**
     * Logfile for the native code.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     */
    static final String DROPBEAR_ERR = "dropbear.err";

    private static final String TAG = "SshdSettings";

    private SshdSettings() {
    }

    /**
     * Enumerate all non-loopback, non-link-local IP addresses on the device.
     *
     * Typically only returns a single address (the active Wi-Fi or cellular interface),
     * but may include multiple if there's a mix of IPv4 and IPv6, or VPN/tethering.
     * IPv6 addresses sort to the end of the result list.
     *
     * @param limit maximum number of addresses to return (see also SshdSettings#getHostAddresses)
     * @return a list of IP address strings, empty if enumeration failed
     */
    @SuppressWarnings("SameParameterValue")
    @NonNull
    public static List<String> getHostAddresses(final int limit) {
        try {
            return Collections
                    .list(NetworkInterface.getNetworkInterfaces())
                    .stream()
                    .flatMap(ni -> Collections.list(ni.getInetAddresses()).stream())
                    .filter(ina -> !ina.isLoopbackAddress() && !ina.isLinkLocalAddress())
                    .map(InetAddress::getHostAddress)
                    .filter(Objects::nonNull)
                    // Sorting moves IPv6 addresses to the end of the list.
                    .sorted()
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (@NonNull final Exception ignore) {
            // ignore
        }

        return new ArrayList<>();
    }

    @NonNull
    static File getDropbearDirectory(@NonNull final Context context) {
        final File path = new File(context.getFilesDir(), ".dropbear");
        if (!path.exists()) {
            path.mkdir();
        }
        return path;
    }

    /**
     * Read the username and hashed password from the dropbear config directory.
     *
     * The file format is single-line: {@code username:SHA-512-base64(password)}
     *
     * @param context Current context
     * @return a String array with [0] username and [1] hashed/base64 password,
     *         or {@code null} if the file is missing or unreadable
     */
    @Nullable
    public static String[] readPasswordFile(@NonNull final Context context) {
        final File path = getDropbearDirectory(context);
        final File file = new File(path, AUTHORIZED_USERS);
        final List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath());
            if (!lines.isEmpty()) {
                final String[] up = lines.get(0).split(":");
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "readPasswordFile"
                               + "|username: " + up[0]
                               + "|password: " + up[1]);
                }
                return up;
            }
        } catch (@NonNull final IOException ignore) {
            // ignore
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "readPasswordFile|null");
        }
        return null;
    }

    /**
     * Write credentials to the dropbear password file.
     *
     * Decision table (see class javadoc for the visual version):
     *
     * username == null/blank  → delete the password file (no credentials)
     * passwordUpdated && password == null/blank → delete (user cleared password)
     * !passwordUpdated && file absent → no-op (nothing to do)
     * !passwordUpdated && file present → update username, keep existing password hash
     * passwordUpdated && password set → hash the password and write user:hash
     *
     * The password is hashed with SHA-512 and base64-encoded before storage.
     * On the native side, the same process is repeated for incoming passwords
     * and compared via strcmp.
     *
     * @param context         Current context
     * @param username        the SSH login username (nullable — clears if null)
     * @param passwordUpdated whether the user edited the password field
     * @param password        the raw password (nullable)
     * @throws IOException              if file operations fail
     * @throws NoSuchAlgorithmException if SHA-512 is unavailable (should never happen)
     */
    public static void writePasswordFile(@NonNull final Context context,
                                         @Nullable final String username,
                                         final boolean passwordUpdated,
                                         @Nullable final String password)
            throws IOException,
                   NoSuchAlgorithmException {

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "writePasswordFile"
                       + "|username: " + username
                       + "|passwordUpdated: " + passwordUpdated
                       + "|password: " + password);
        }

        final File path = getDropbearDirectory(context);
        final File file = new File(path, AUTHORIZED_USERS);
        final boolean fileExists = file.exists();

        // below code is on purpose NOT simplified/combined for readability.

        // If we have no username,
        // Remove the credentials file.
        if (username == null || username.isBlank()) {
            if (fileExists) {
                file.delete();
            }
            return;
        }

        // At this point, we always have a non-blank username.

        // If the user explicitly removed the password,
        // Silently drop the username.
        // Remove the credentials file.
        //
        //
        if (passwordUpdated && (password == null || password.isBlank())) {
            if (fileExists) {
                file.delete();
            }
            return;
        }

        // If the file does NOT exist, we silently drop the username, and we're done.
        if (!passwordUpdated && !fileExists) {
            return;
        }

        // If the user did NOT change the password
        if (!passwordUpdated) {
            // The file exists; we must update the username, but keep the previous password.
            // Retrieve the previously hashed password,
            final String[] previous = readPasswordFile(context);
            // and rewrite the file using the new username
            // and the retrieved hashed password.
            // i.e. REPLACE username, KEEP previous password
            if (previous != null && previous.length == 2) {
                writeFile(file, username, previous[1]);
                return;
            } else {
                // We failed to read from the existing file? This should not be happening
                // unless for example the user manually fiddled with the file/permissions.
                throw new IOException("Could not read previous user/password");
            }
        }

        // We have a new user and an updated non-blank password.
        // Create the file if it does not exist yet.
        file.createNewFile();
        // and write the user and hashed password to the file.
        writeFile(file, username, hash(password));
    }

    private static void writeFile(@NonNull final File file,
                                  @NonNull final String username,
                                  @NonNull final String hash)
            throws IOException {
        try (FileWriter fw = new FileWriter(file)) {
            fw.write((username + ":" + hash).toCharArray());
        }
    }

    /**
     * Hash a password with SHA-512 and encode as base64.
     *
     * The native C layer (ikofi_svr_auth_password in jni-dropbear.c) performs
     * the identical hashing so that a strcmp between the stored hash and the
     * hash of the incoming password yields equality on a correct login.
     *
     * @param password raw password to hash
     * @return base64-encoded SHA-512 digest
     * @throws NoSuchAlgorithmException if SHA-512 is not available
     */
    @NonNull
    private static String hash(@NonNull final String password)
            throws NoSuchAlgorithmException {
        final MessageDigest md = MessageDigest.getInstance("SHA-512");
        final byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }
}
