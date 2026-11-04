package org.ttlink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts ttlink after boot when "Start at boot" is ticked. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        if (!c.getSharedPreferences("ttlink", 0).getBoolean("autostart", false)) return;
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread() {
            public void run() {
                Ttlink.start(app);
                pr.finish();
            }
        }.start();
    }
}
