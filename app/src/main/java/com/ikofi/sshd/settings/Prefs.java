package com.ikofi.sshd.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User settings.
 */
public final class Prefs {

    /** boolean. */
    static final String RUN_IN_FOREGROUND = "service.start.foreground";
    /** boolean. */
    static final String RUN_ON_BOOT = "service.start.onboot";

    static final String ON_APP_EXIT_KEEP_RUNNING = "service.onappexit.keep_running";

    /** String. */
    static final String SSHD_PORT = "sshd.port";
    /** String. */
    static final int DEFAULT_PORT = 2222;
    /** String. */
    static final String DROPBEAR_CMDLINE_OPTIONS = "dropbear.options";

    /** boolean. */
    private static final String ENABLE_SERVICE_SHELL_ACCESS = "sshd.enable.service.shell";
    /** boolean. */
    private static final String ENABLE_SINGLE_USE_PASSWORDS = "sshd.enable.single.use.password";
    /** boolean. */
    private static final String ENABLE_PUBLIC_KEY_LOGIN = "sshd.enable.publickey.login";
    private static final String ENV_VARS = "sshd.env";
    private static final String HOME = "sshd.home";
    private static final String SHELL = "sshd.shell";
    private static final String DEFAULT_SHELL = "/system/bin/sh";

    /** boolean. */
    private static final String RUN_ON_APP_START = "service.start.onopen";
    /** boolean. */
    private static final String RUN_ON_INTENT_ALLOWED = "service.start.on.intent.allowed";

    /**
     * Extra command line options to pass to the dropbear executable.
     * Splits on spaces, but respects " and \
     */
    private static final Pattern CMD_OPTIONS_PATTERN = Pattern.compile(
            "\\S*\"([^\"]*)\"\\S*|(\\S+)");

    private Prefs() {
    }

    /**
     * Start the service when the device is booted.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartOnBoot(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_ON_BOOT, false);
    }

    /**
     * Start the service when the app is started.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isRunOnAppStart(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_ON_APP_START, false);
    }

    /**
     * Whether an external Intent can start app+service and stop the service+app.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartByIntentAllowed(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_ON_INTENT_ALLOWED, false);
    }

    /**
     * Whether a user/manually started service should be stopped
     * when the app enters {@code #onDestroy()}.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isKeepRunningOnAppExit(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(ON_APP_EXIT_KEEP_RUNNING, false);
    }

    /**
     * How the service should be handled by the system when the App goes to the background.
     *
     * @param context Current context
     *
     * @return {@code true}: the service should keep running
     *         {@code false}: the system can kill the service
     */
    public static boolean isRunInForeground(@NonNull final Context context) {
        return isRunInForeground(PreferenceManager.getDefaultSharedPreferences(context));
    }

    /**
     * How the service should be handled by the system when the App goes to the background.
     *
     * @param prefs to read from
     *
     * @return {@code true}: the service should keep running
     *         {@code false}: the system can kill the service
     */
    public static boolean isRunInForeground(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(RUN_IN_FOREGROUND, true);
    }

    /**
     * Get the bindings as configured.
     *
     * @param prefs to read from
     *
     * @return list of "[address:]port" strings
     */
    @NonNull
    public static List<String> getBindings(@NonNull final SharedPreferences prefs) {
        final List<String> userOptions = getCmdLineOptions(prefs);
        if (userOptions.contains("-p")) {
            return collectBindings(userOptions);
        } else {
            return List.of("*:" + getPort(prefs));
        }
    }

    /**
     * Collect all user configured bindings, we're NOT checking validity.
     * 1. we're assuming the user knows what they are doing... flw
     * 2. broken options will be detected in dropbear
     *
     * @param userOptions to parse
     *
     * @return list of "-p" arguments, i.e. the "addr:port" settings
     */
    @VisibleForTesting
    @NonNull
    public static List<String> collectBindings(@NonNull final List<String> userOptions) {
        final List<String> bindings = new ArrayList<>();
        final Iterator<String> it = userOptions.iterator();
        while (it.hasNext()) {
            final String s = it.next();
            if ("-p".equals(s) && it.hasNext()) {
                bindings.add(it.next());
            }
        }
        return bindings;
    }

    /**
     * The user configured port to listen on.
     *
     * @param prefs to read from
     *
     * @return port as a {@code String}
     */
    public static int getPort(@NonNull final SharedPreferences prefs) {
        final String ps = prefs.getString(Prefs.SSHD_PORT, null);
        if (ps != null && !ps.isBlank()) {
            //noinspection OverlyBroadCatchBlock
            try {
                return Integer.parseInt(ps);
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }

        return DEFAULT_PORT;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return options
     */
    @NonNull
    public static List<String> getCmdLineOptions(@NonNull final SharedPreferences prefs) {
        return splitOptions(prefs.getString(DROPBEAR_CMDLINE_OPTIONS, ""));
    }

    /**
     * Split the single String with options in a list.
     *
     * @param options to split
     *
     * @return list
     */
    @VisibleForTesting
    @NonNull
    public static List<String> splitOptions(@NonNull final CharSequence options) {
        final List<String> argList = new ArrayList<>();
        final Matcher matcher = CMD_OPTIONS_PATTERN.matcher(options);
        while (matcher.find()) {
            argList.add(matcher.group());
        }
        return argList;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * Returns the SSH home directory.  Defaults to the device's external
     * storage root (/sdcard / /storage/emulated/0).  Falls back to the
     * app's private files dir when external storage is not available.
     *
     * @param context Current context
     * @param prefs   to read from
     *
     * @return path
     */
    @NonNull
    public static String getHomePath(@NonNull final Context context,
                                     @NonNull final SharedPreferences prefs) {
        String homePath = prefs.getString(HOME, null);
        // If never set, default to external storage (/sdcard).
        if (homePath == null || !new File(homePath).exists()) {
            final File extStorage = Environment.getExternalStorageDirectory();
            if (extStorage != null && extStorage.exists()) {
                homePath = extStorage.getPath();
            } else {
                homePath = context.getFilesDir().getPath();
            }
            prefs.edit().putString(HOME, homePath).apply();
        }
        return homePath;
    }

    /**
     * Allow remote shell access to the device.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnableServiceShellAccess(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_SERVICE_SHELL_ACCESS, true);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return path
     */
    @NonNull
    public static String getShellCmd(@NonNull final SharedPreferences prefs) {
        String shellCmd = prefs.getString(SHELL, null);
        if (shellCmd == null || !new File(shellCmd).exists()) {
            shellCmd = DEFAULT_SHELL;
        }
        return shellCmd;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return env vars
     */
    @NonNull
    public static String getEnv(@NonNull final SharedPreferences prefs) {
        return prefs.getString(ENV_VARS, "");
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnablePublicKeyAuth(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_PUBLIC_KEY_LOGIN, true);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnableSingleUsePasswordAuth(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_SINGLE_USE_PASSWORDS, true);
    }
}
