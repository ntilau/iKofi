package com.hardbacknutter.sshd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import com.hardbacknutter.sshd.databinding.DialogAboutBinding;
import com.hardbacknutter.sshd.databinding.FragmentMainBinding;
import com.hardbacknutter.sshd.settings.Prefs;
import com.hardbacknutter.sshd.settings.SettingsFragment;

public class MainFragment
        extends Fragment {

    static final String TAG = "MainFragment";
    /** boolean. */
    private static final String PK_UI_NOTIFICATION_ASK_PERMISSION =
            "ui.notification.ask_permission";
    /** boolean. */
    private static final String PK_UI_ASK_TO_ADD_TILE = "ui.tile.ask";

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), isGranted -> {
                        if (!isGranted) {
                            //noinspection DataFlowIssue
                            PreferenceManager
                                    .getDefaultSharedPreferences(getContext())
                                    .edit()
                                    .putBoolean(PK_UI_NOTIFICATION_ASK_PERMISSION, false)
                                    .apply();
                        }
                    });

    private ServiceViewModel vm;

    /**
     * Listen for broadcasts from the service informing us about any changes.
     * We simply react by updating the UI.
     */
    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            vm.updateUI();
        }
    };
    private FragmentMainBinding vb;
    private ActivityResultLauncher<String> authKeysImportLauncher;
    @Nullable
    private ToolbarMenuProvider toolbarMenuProvider;
    private boolean isTelevision;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection DataFlowIssue
        isTelevision = getContext().getPackageManager()
                                   .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        vb = FragmentMainBinding.inflate(inflater, container, false);
        return vb.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(null);

        if (isTelevision) {
            vb.tvButtons.setVisibility(View.VISIBLE);

            vb.tvBtnStartAction.setOnClickListener(v -> onMenu(R.id.start_action));
            vb.tvBtnSettings.setOnClickListener(v -> onMenu(R.id.settings));
            vb.tvBtnImportKeys.setOnClickListener(v -> onMenu(R.id.menu_import_keys));
            vb.tvBtnResetKeys.setOnClickListener(v -> onMenu(R.id.menu_reset_keys));
            vb.tvBtnHelp.setOnClickListener(v -> onMenu(R.id.menu_help));
            vb.tvBtnAbout.setOnClickListener(v -> onMenu(R.id.about));

            vb.log.setSelectAllOnFocus(true);
            vb.logScroller.setFocusable(false);

            // up/down loops through the log view and all buttons.
            // left/right not defined
            vb.log.setNextFocusDownId(vb.tvBtnStartAction.getId());
            vb.log.setNextFocusUpId(vb.tvBtnAbout.getId());
            vb.tvBtnStartAction.setNextFocusUpId(vb.log.getId());
            vb.tvBtnAbout.setNextFocusDownId(vb.log.getId());

        } else {
            vb.tvButtons.setVisibility(View.GONE);
            toolbarMenuProvider = new ToolbarMenuProvider();
            toolbar.addMenuProvider(toolbarMenuProvider, getViewLifecycleOwner());
        }

        //noinspection DataFlowIssue
        vm = new ViewModelProvider(getActivity()).get(ServiceViewModel.class);
        vm.forceLogUpdate();

        vm.onLogUpdate().observe(getViewLifecycleOwner(), output -> {
            // We always replace the WHOLE content.
            vb.log.setText(String.join("\n", output));
            vb.logScroller.post(() -> vb.logScroller.fullScroll(ScrollView.FOCUS_DOWN));
        });
        vm.onUpdateUi().observe(getViewLifecycleOwner(), aVoid -> onUpdateUi());

        authKeysImportLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImportAuthKeys);

        //noinspection DataFlowIssue
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (prefs.getBoolean(PK_UI_ASK_TO_ADD_TILE, true)) {
            askToAddTile();
            // We only ask once
            prefs.edit().putBoolean(PK_UI_ASK_TO_ADD_TILE, false).apply();
        }
    }

    private void askToAddTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askV33();
        } else {
            askV30();
        }
    }

    private void askV30() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.app_name)
                .setIcon(R.drawable.app_tile)
                .setMessage(R.string.info_add_tile)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    @RequiresApi(api = 33)
    private void askV33() {
        final Context context = requireContext();
        final StatusBarManager sbm = context.getSystemService(StatusBarManager.class);
        if (sbm == null) {
            // Should never get here... flw
            Log.e(TAG, "No StatusBarManager service found");
            return;
        }

        final ComponentName tileService = new ComponentName(context, SshdTileService.class);
        sbm.requestAddTileService(
                tileService,
                getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.app_tile),
                Runnable::run,
                result -> {
                    switch (result) {
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
                            //noinspection DataFlowIssue
                            Snackbar.make(getView(), R.string.tile_added,
                                          Snackbar.LENGTH_SHORT).show();
                            // Not sure if this is really needed?
                            TileService.requestListeningState(context, tileService);
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED:
                            //noinspection DataFlowIssue
                            Snackbar.make(getView(), R.string.tile_not_added,
                                          Snackbar.LENGTH_SHORT).show();
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                            //noinspection DataFlowIssue
                            Snackbar.make(getView(), R.string.tile_already_added,
                                          Snackbar.LENGTH_SHORT).show();
                            break;
                    }
                });
    }

    private void requestListeningState(@NonNull final Context context) {
        final ComponentName tileService = new ComponentName(context, SshdTileService.class);
        TileService.requestListeningState(context, tileService);
    }

    @Override
    public void onResume() {
        super.onResume();

        // This is quite essential, without this permission the user cannot really use rsync/sftp
        if (!Environment.isExternalStorageManager()
            && !vm.isSpecialPermDialogShowing()) {
            requestFileAccessPermission();
        }

        // This is optional and only needed from Android 13 up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission();
        }

        //noinspection ConstantConditions,deprecation
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(
                messageReceiver, new IntentFilter(SshdService.SERVICE_UI_REQUEST));

        // Race situation if the service does not stop fast enough....
        // 1. device in portrait; start-on-app-start
        // 2. running==false
        // 3. starts
        // 4. ROTATE, the service is triggered to stop running
        // 5. onResume... service still running
        // 6. so we don't start it.
        // 7. the service is finally stopped, and we have NOT (re)started it
        // 2025-04-12: won't fix... unless some user complains very hard...
        // ... and that might result in locking the rotation of the device for this app.
        if (SshdService.isRunning()) {
            vm.startUpdateThread(getContext());

        } else if (Prefs.isRunOnAppStart(getContext())) {
            vm.startService(getContext(), StartMode.ByUser);
        }

        onUpdateUi();
        populateNetworkAddressList();

        if (isTelevision) {
            vb.tvBtnStartAction.requestFocus();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestNotificationPermission() {
        //noinspection ConstantConditions
        if (ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {

            if (PreferenceManager.getDefaultSharedPreferences(getContext())
                                 .getBoolean(PK_UI_NOTIFICATION_ASK_PERMISSION, true)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * GitHub #12:
     * Android/Google TV 11 & 12:
     * The standard Google image does not provide the needed Dialog.
     * Some vendors <strong>might</strong> add them.
     * <p>
     * Android/Google TV 13 & 14: the dialog is always available.
     */
    private void requestFileAccessPermission() {

        final Context context = requireContext();

        final Intent intent = new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.getPackageName(), null));

        @SuppressLint("QueryPermissionsNeeded")
        final List<ResolveInfo> resInfoList = context
                .getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_ALL);

        if (resInfoList.isEmpty()) {
            potentiallyUnsupportedDevice();
            return;
        }

        //noinspection ConstantConditions
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.security_24px)
                .setTitle(R.string.dialog_title_attention)
                .setMessage(R.string.msg_request_files_management)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    d.dismiss();
                    vm.setSpecialPermDialogShowing(false);
                })
                .setPositiveButton(R.string.ok, (d, w) -> {
                    d.dismiss();
                    vm.setSpecialPermDialogShowing(false);
                    try {
                        startActivity(intent);
                    } catch (@NonNull final ActivityNotFoundException e) {
                        unsupportedDevice();
                    }
                })
                .create()
                .show();

        vm.setSpecialPermDialogShowing(true);
    }

    /**
     * The intent to start {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION} failed.
     * The user is likely on an Android-TV 11 or 12,
     * or on an Android-Wear device.
     * <p>
     * It <strong>might</strong> be possible to manually grant permissions.
     * Take the user to the project help pages.
     */
    private void potentiallyUnsupportedDevice() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.warning_24px)
                .setMessage(R.string.err_device_maybe_not_supported)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d, w) -> {
                    d.dismiss();
                    gotoProjectHelp();
                })
                .create()
                .show();
    }

    /**
     * Fatal. The device claimed to support
     * {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}
     * but failed to start the Activity.
     * We should never get here... flw
     */
    private void unsupportedDevice() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.error_24px)
                .setMessage(R.string.err_unexpected_error)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d, w) -> {
                    d.dismiss();
                    gotoProjectIssues();
                })
                .create()
                .show();
    }

    @Override
    public void onPause() {
        vm.cancelUpdateThread();
        //noinspection ConstantConditions,deprecation
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(messageReceiver);
        super.onPause();
    }

    /**
     * When started {@link StartMode#ByUser} stop it.
     * If we were started at app-start, then onResume will restart us.
     * <p>
     * <strong>NEVER STOP</strong> the service if started
     * <ul>
     *     <li>{@link StartMode#ByIntent}</li>
     *     <li>{@link StartMode#OnBoot}</li>
     * </ul>
     */
    @Override
    public void onDestroy() {
        if (vm != null) {
            final Context context = requireContext();
            if (SshdService.getStartMode() == StartMode.ByUser
                && !Prefs.isKeepRunningOnAppExit(context)) {
                vm.stopService(context);
            }
        }
        super.onDestroy();
    }

    private void onUpdateUi() {
        if (toolbarMenuProvider != null) {
            updateStartButton(toolbarMenuProvider.startButton);
        } else if (isTelevision) {
            updateStartButton(vb.tvBtnStartAction);
        }

        // We're setting this regardless of StartMode.
        // If we have a UI, then we always want this flag set while running
        // to prevent the device going to sleep and slowing down transfers.
        if (SshdService.isRunning()) {
            //noinspection DataFlowIssue
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            //noinspection DataFlowIssue
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        populateNetworkAddressList();
    }

    private void populateNetworkAddressList() {
        // Show all interfaces, limited to 6...
        final List<String> iList = SshdSettings.getHostAddresses(6);
        if (iList.isEmpty()) {
            // should never happen... flw
            vb.allInterfaces.setText(R.string.err_no_ip);
        } else {
            vb.allInterfaces.setText(String.join("\n", iList));
        }

        // The configured interface listener(s)
        //noinspection ConstantConditions
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getContext());
        final List<String> bindings = Prefs.getBindings(prefs);
        if (bindings.isEmpty()) {
            vb.listeners.setText(R.string.err_no_ip);
        } else {
            vb.listeners.setText(String.join("\n", bindings));
        }
    }

    private void onImportAuthKeys(@Nullable final Uri uri) {
        if (uri != null) {
            //noinspection ConstantConditions
            final String error = vm.importAuthKeys(getContext(), uri);
            if (error != null) {
                // note that the state of the current key file is unknown at this point
                new MaterialAlertDialogBuilder(getContext())
                        .setIcon(R.drawable.error_24px)
                        .setTitle(R.string.dialog_title_attention)
                        .setMessage(error)
                        .setCancelable(true)
                        .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                        .create()
                        .show();
            }
        }
    }

    private void showResetKeys() {
        final Context context = getContext();
        //noinspection ConstantConditions
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.warning_24px)
                .setTitle(R.string.lbl_reset_keys_long)
                .setMessage(R.string.confirm_reset_keys)
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d, w) -> vm.deleteAuthKeys(context))
                .create()
                .show();
    }

    private void showAbout() {
        final Context context = getContext();
        String version;
        //noinspection OverlyBroadCatchBlock
        try {
            //noinspection ConstantConditions
            version = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;

        } catch (@NonNull final Exception e) {
            version = getString(R.string.err_no_version);
        }

        final DialogAboutBinding dvb = DialogAboutBinding.inflate(getLayoutInflater());
        dvb.version.setText(getString(R.string.about_versions,
                                      SshdService.getDropbearVersion(),
                                      SshdService.getRsyncVersion(),
                                      SshdService.getOpensshVersion()));

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.info_24px)
                .setTitle(getString(R.string.about_title, version))
                .setView(dvb.getRoot())
                .setCancelable(true)
                .setNeutralButton(R.string.lbl_github_project, (d, w) -> gotoProject())
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    private void gotoProjectHelp() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                getString(R.string.github_project_docs))));
    }

    private void gotoProjectIssues() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                getString(R.string.github_project_issues))));
    }

    private void gotoProject() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                getString(R.string.github_project_url))));
    }

    @SuppressWarnings("SameParameterValue")
    private void replaceFragment(@NonNull final Class<? extends Fragment> fragmentClass,
                                 @NonNull final String tag) {
        final FragmentManager fm = getParentFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fm.beginTransaction()
              .addToBackStack(tag)
              .setReorderingAllowed(true)
              .replace(R.id.fragment_container, fragmentClass, null, tag)
              .commit();
        }
    }

    /**
     * React to both the options-menu and television button-menu.
     *
     * @param itemId selected
     *
     * @return {@code true} if handled
     */
    private boolean onMenu(final int itemId) {
        if (itemId == R.id.start_action) {
            if (SshdService.isRunning()) {
                //noinspection ConstantConditions
                vm.stopService(getContext());
            } else {
                //noinspection ConstantConditions
                if (!vm.startService(getContext(), StartMode.ByUser)) {
                    //noinspection ConstantConditions
                    Snackbar.make(getView(), R.string.err_service_failed_to_start,
                                  Snackbar.LENGTH_LONG).show();
                }
            }
        } else if (itemId == R.id.settings) {
            replaceFragment(SettingsFragment.class, SettingsFragment.TAG);
            return true;
        } else if (itemId == R.id.menu_import_keys) {
            authKeysImportLauncher.launch("*/*");
            return true;
        } else if (itemId == R.id.menu_reset_keys) {
            showResetKeys();
            return true;
        } else if (itemId == R.id.menu_help) {
            gotoProjectHelp();
            return true;
        } else if (itemId == R.id.about) {
            showAbout();
            return true;
        }
        return false;
    }

    /**
     * Update text/color of the "start" button.
     *
     * @param startButton either the toolbar or the TV button.
     */
    private void updateStartButton(@NonNull final Button startButton) {
        if (SshdService.isRunning()) {
            startButton.setText(R.string.lbl_stop);
            startButton.setTextColor(getColor(R.attr.stopButtonTextColor));
        } else {
            startButton.setText(R.string.lbl_start);
            startButton.setTextColor(getColor(R.attr.startButtonTextColor));
        }
    }

    @ColorInt
    private int getColor(@AttrRes final int colorAttrId) {
        //noinspection DataFlowIssue
        final TypedArray a = getContext()
                .obtainStyledAttributes(new int[]{colorAttrId});
        try {
            return a.getColor(0, 0);
        } finally {
            a.recycle();
        }
    }

    private class ToolbarMenuProvider
            implements MenuProvider {

        private Button startButton;

        @Override
        public void onCreateMenu(@NonNull final Menu menu,
                                 @NonNull final MenuInflater menuInflater) {
            menuInflater.inflate(R.menu.main_menu, menu);

            final MenuItem menuItem = menu.findItem(R.id.start_action);
            //noinspection DataFlowIssue
            startButton = menuItem.getActionView().findViewById(R.id.btn_start);
            startButton.setOnClickListener(v -> onMenu(R.id.start_action));
        }

        @Override
        public boolean onMenuItemSelected(@NonNull final MenuItem menuItem) {
            return onMenu(menuItem.getItemId());
        }
    }
}
