package org.ttlink;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** One screen: status, Start/Stop, pairing, TomTom key, start at boot, log. */
public class MainActivity extends Activity {
    private TextView status, detail, log;
    private Button startStop;
    private ScrollView logScroll;
    private final Handler h = new Handler();
    private boolean busy;

    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            h.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(22);
        status.setTypeface(null, Typeface.BOLD);
        root.addView(status);
        detail = new TextView(this);
        detail.setTextSize(15);
        detail.setPadding(0, dp(4), 0, dp(8));
        root.addView(detail);

        LinearLayout row = new LinearLayout(this);
        startStop = new Button(this);
        startStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        row.addView(startStop, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Button pair = new Button(this);
        pair.setText("Visible for pairing");
        pair.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivity(i);
            }
        });
        row.addView(pair, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        TextView keyLabel = new TextView(this);
        keyLabel.setText("TomTom traffic key (free at developer.tomtom.com)");
        keyLabel.setPadding(0, dp(12), 0, 0);
        root.addView(keyLabel);
        LinearLayout keyRow = new LinearLayout(this);
        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(Ttlink.conf(this, "tomtom_key"));
        keyRow.addView(key, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Ttlink.saveConf(MainActivity.this, key.getText().toString());
                    toast(Ttlink.running(MainActivity.this) ? "Saved. Stop and start to use it." : "Saved.");
                } catch (Exception e) {
                    toast("Could not save: " + e.getMessage());
                }
            }
        });
        keyRow.addView(save);
        root.addView(keyRow);

        final SharedPreferences prefs = getSharedPreferences("ttlink", 0);
        CheckBox boot = new CheckBox(this);
        boot.setText("Start at boot");
        boot.setChecked(prefs.getBoolean("autostart", false));
        boot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("autostart", on).commit();
            }
        });
        root.addView(boot);

        TextView logLabel = new TextView(this);
        logLabel.setText("Log");
        logLabel.setPadding(0, dp(8), 0, 0);
        root.addView(logLabel);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        logScroll = new ScrollView(this);
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void toggle() {
        if (busy) return;
        busy = true;
        final boolean stop = Ttlink.running(this);
        startStop.setEnabled(false);
        startStop.setText(stop ? "Stopping..." : "Starting...");
        new Thread() {
            public void run() {
                final int rc = stop ? Ttlink.stop(MainActivity.this) : Ttlink.start(MainActivity.this);
                try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                h.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (rc != 0) toast("Root access failed (su returned " + rc + ")");
                        refresh();
                    }
                });
            }
        }.start();
    }

    private void refresh() {
        boolean on = Ttlink.running(this);
        status.setText(on ? "Running" : "Stopped");
        status.setTextColor(on ? Color.rgb(90, 200, 90) : Color.rgb(220, 120, 80));
        if (!busy) {
            startStop.setEnabled(true);
            startStop.setText(on ? "Stop" : "Start");
        }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        String btState = bt == null ? "no Bluetooth" : bt.isEnabled() ? "Bluetooth on" : "Bluetooth OFF: turn it on";
        String text = "";
        try {
            text = Ttlink.read(new File(Ttlink.dir(this), "ttlink.log"), 6000);
            int nl = text.indexOf('\n');
            if (text.length() >= 6000 && nl >= 0) text = text.substring(nl + 1);
        } catch (Exception ignored) {
        }
        detail.setText(btState + "\n" + (on ? lastEvent(text) : "Press Start, then pair the TomTom (PIN 0000)."));
        boolean atEnd = logScroll.getScrollY() + logScroll.getHeight() >= log.getHeight() - dp(20);
        log.setText(text);
        if (atEnd) logScroll.post(new Runnable() {
            public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    /** A plain summary of the newest interesting log line. */
    private static String lastEvent(String log) {
        String[] lines = log.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i];
            if (l.contains("ppp: link closed")) return "TomTom disconnected";
            if (l.contains("ppp: up")) return "TomTom online";
            if (l.contains("dial: PPP up")) return "TomTom dialling in...";
            if (l.contains("connection from")) return "TomTom connected";
            if (l.contains("waiting for the TomTom")) return "Waiting for the TomTom";
        }
        return "Starting...";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
