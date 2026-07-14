package com.ikofi.sshd;

import android.content.ComponentName;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.NonNull;

public class SshdTileService
        extends TileService {
    private static final String TAG = "SshdTileService";

    @Override
    public void onTileAdded() {
        final boolean running = SshdService.isRunning();

        if (BuildConfig.DEBUG) {
            Log.d(TAG + "|onAdded", "ENTER|running=" + running);
        }
        setRunningState(running);
    }

    @Override
    public void onStartListening() {
        final boolean running = SshdService.isRunning();

        if (BuildConfig.DEBUG) {
            Log.d(TAG + "|onStart", "ENTER|running=" + running);
        }
        setRunningState(running);
    }

    @Override
    public void onStopListening() {
        final boolean running = SshdService.isRunning();

        if (BuildConfig.DEBUG) {
            Log.d(TAG + "|onStop", "ENTER|running=" + running);
        }
        setRunningState(running);
    }

    @Override
    public void onClick() {
        final boolean running = SshdService.isRunning();

        if (BuildConfig.DEBUG) {
            Log.d(TAG + "|onClick", "ENTER|running=" + running);
        }

        if (running) {
            SshdService.stopService(this);
            setRunningState(false);

        } else {
            ComponentName componentName = null;
            try {
                componentName = SshdService.startService(this, StartMode.ByUser);
            } catch (@NonNull final Exception e) {
                // On devices with API 31, theoretically we could see these:
                // ForegroundServiceStartNotAllowedException
                // BackgroundServiceStartNotAllowedException
                // ... but we shouldn't... flw
                Log.e(TAG, "", e);
            }

            if (componentName == null) {
                // It's unlikely we ever get here. Up to now, I've never had the service
                // failing to start... flw
                // TODO: we may want to use #showDialog(); instead.
                setErrorState();
            } else {
                setRunningState(true);
            }
        }
    }

    private void setRunningState(final boolean running) {
        final Tile tile = getQsTile();
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(running ? R.string.enabled : R.string.disabled));
        tile.updateTile();
    }

    private void setErrorState() {
        final Tile tile = getQsTile();
        tile.setState(Tile.STATE_UNAVAILABLE);
        tile.setSubtitle(getString(R.string.err_see_app));
        tile.updateTile();
    }
}
