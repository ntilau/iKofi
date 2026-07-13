package com.hardbacknutter.sshd.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import com.hardbacknutter.sshd.R;
import com.hardbacknutter.sshd.SshdSettings;
import com.hardbacknutter.util.theme.NightMode;

public class SettingsFragment
        extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String TAG = "SettingsFragment";
    private static final String PSK_DROPBEAR_SSH = "psk_dropbear_ssh";
    private static final int PORT_MIN = 1024;
    private static final int PORT_MAX = 32768;

    private SwitchPreference pRunOnBoot;
    private SwitchPreference pRunInForeground;
    private EditTextPreference pPort;
    private EditTextPreference pUsername;
    private EditTextPreference pPassword;
    private boolean isTelevision;
    private SettingsViewModel vm;

    private final OnBackPressedCallback backPressedCallback =
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    try {
                        //noinspection DataFlowIssue
                        vm.getDsUP().storeCredentials(getContext());

                    } catch (@NonNull final IOException | NoSuchAlgorithmException ignore) {
                        // we should never get here... flw
                        //noinspection DataFlowIssue
                        Snackbar.make(getView(), R.string.err_failed_to_save,
                                      Snackbar.LENGTH_LONG).show();
                        getView().postDelayed(() -> getParentFragmentManager().popBackStack(),
                                              2_000);
                    }

                    if (vm.hasAtLeastOneAuthMethod(getContext())) {
                        getParentFragmentManager().popBackStack();
                    } else {
                        new MaterialAlertDialogBuilder(getContext())
                                .setIcon(R.drawable.warning_24px)
                                .setTitle(R.string.dialog_title_warning)
                                .setMessage(R.string.warning_no_auth_methods)
                                .setCancelable(true)
                                .setNegativeButton(R.string.cancel, (d, which) ->
                                        d.dismiss())
                                .setPositiveButton(R.string.ok, (d, which) ->
                                        getParentFragmentManager().popBackStack())
                                .create()
                                .show();
                    }
                }
            };

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection DataFlowIssue
        isTelevision = getContext().getPackageManager()
                                   .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                                    @Nullable final String rootKey) {
        final Context context = getContext();

        vm = new ViewModelProvider(this).get(SettingsViewModel.class);
        vm.init(context);

        setPreferencesFromResource(R.xml.preferences, rootKey);

        final Preference pUiTheme = findPreference(NightMode.PK_UI_THEME_MODE);
        //noinspection ConstantConditions
        pUiTheme.setOnPreferenceChangeListener((p, newValue) -> {
            // we should never have an invalid setting in the prefs... flw
            try {
                final int mode = Integer.parseInt(String.valueOf(newValue));
                NightMode.apply(mode);
                return true;

            } catch (@NonNull final NumberFormatException ignore) {
                // ignore
            }
            return false;
        });

        pRunOnBoot = findPreference(Prefs.RUN_ON_BOOT);
        pRunInForeground = findPreference(Prefs.RUN_IN_FOREGROUND);

        final String interfaces =
                getString(R.string.lbl_all_interfaces)
                + '\n'
                + String.join("\n", SshdSettings.getHostAddresses(6));
        findPreference(PSK_DROPBEAR_SSH).setSummary(interfaces);

        pPort = findPreference(Prefs.SSHD_PORT);
        pPort.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.selectAll();
        });
        setPortPrefEnabled(getPreferenceManager().getSharedPreferences());

        pUsername = findPreference(UserPassStorage.PK_SSHD_AUTH_USERNAME);
        pUsername.setPreferenceDataStore(vm.getDsUP());

        pPassword = findPreference(UserPassStorage.PK_SSHD_AUTH_PASSWORD);
        pPassword.setPreferenceDataStore(vm.getDsUP());
        pPassword.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_TEXT
                                  | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editText.selectAll();
        });
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (isTelevision) {
            // Needed as a workaround to be able to get to the last option
            // when using the d-pad 'down' key.
            final RecyclerView recyclerView = getListView();
            recyclerView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            final int p = getResources().getDimensionPixelSize(R.dimen.tv_prefs_padding_bottom);
            recyclerView.setPadding(0, 0, 0, p);
        }

        //noinspection ConstantConditions
        getActivity().getOnBackPressedDispatcher()
                     .addCallback(getViewLifecycleOwner(), backPressedCallback);

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationIcon(R.drawable.arrow_back_24px);
        }

        // init before setting the field text
        vm.onPasswordSummaryUpdate().observe(getViewLifecycleOwner(), s ->
                pPassword.setSummary(s));

        pUsername.setText(vm.getDsUP().getCurrentUsername());
        // initially ALWAYS null, but will contain the current unencrypted
        // password after the user has entered it once during this settings-session
        pPassword.setText(vm.getDsUP().getCurrentPassword());
    }

    @Override
    public void onResume() {
        super.onResume();
        //noinspection ConstantConditions
        getPreferenceScreen().getSharedPreferences()
                             .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        //noinspection ConstantConditions
        getPreferenceScreen().getSharedPreferences()
                             .unregisterOnSharedPreferenceChangeListener(this);

        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(@NonNull final SharedPreferences prefs,
                                          @Nullable final String key) {
        // Sanity check
        if (key == null) {
            return;
        }

        switch (key) {
            case Prefs.RUN_ON_BOOT:
            case Prefs.RUN_IN_FOREGROUND: {
                if (pRunOnBoot.isChecked() && !pRunInForeground.isChecked()) {
                    pRunInForeground.setChecked(true);
                }
                break;
            }
            case Prefs.SSHD_PORT: {
                final int port = Prefs.getPort(prefs);
                if (port < PORT_MIN || port > PORT_MAX) {
                    // Setting this will trigger another onSharedPreferenceChanged
                    // but that's fine.
                    pPort.setText(String.valueOf(Prefs.DEFAULT_PORT));
                    //noinspection ConstantConditions
                    Snackbar.make(getView(), R.string.err_port_number,
                                  Snackbar.LENGTH_LONG)
                            .show();
                }
                break;
            }
            case Prefs.DROPBEAR_CMDLINE_OPTIONS: {
                setPortPrefEnabled(prefs);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Enable/Disable the port field to clarify it will be ignored
     * when there is an explicit "-p" option.
     *
     * @param prefs to use
     */
    private void setPortPrefEnabled(@NonNull final SharedPreferences prefs) {
        //
        final String s = prefs.getString(Prefs.DROPBEAR_CMDLINE_OPTIONS, null);
        pPort.setEnabled(s == null || !s.contains("-p"));
    }
}
