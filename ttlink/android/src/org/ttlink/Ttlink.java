package org.ttlink;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** Starts and stops the ttlink binary (shipped as lib/armeabi/libttlink.so) as root through su. */
final class Ttlink {
    private Ttlink() {}

    static File dir(Context c) { return c.getFilesDir(); }

    static String bin(Context c) { return c.getApplicationInfo().nativeLibraryDir + "/libttlink.so"; }

    /** Runs commands in a root shell; returns su's exit code (or -1 if su is missing or was denied). */
    static int su(String script) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            OutputStream o = p.getOutputStream();
            o.write((script + "\nexit\n").getBytes("UTF-8"));
            o.flush();
            o.close();
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    static int pid(Context c) {
        try {
            return Integer.parseInt(read(new File(dir(c), "ttlink.pid"), 32).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean running(Context c) {
        int pid = pid(c);
        if (pid <= 0) return false;
        try {
            return read(new File("/proc/" + pid + "/cmdline"), 512).contains("ttlink");
        } catch (Exception e) {
            return false;
        }
    }

    /** Blocking (su may ask the user): call from a background thread. */
    static int start(Context c) {
        stop(c);
        String d = dir(c).getAbsolutePath();
        return su("cd '" + d + "' && '" + bin(c) + "' -daemon -dir '" + d + "' </dev/null >/dev/null 2>&1 &");
    }

    static int stop(Context c) {
        int pid = pid(c);
        if (pid <= 0 || !running(c)) return 0;
        return su("kill " + pid);
    }

    static String conf(Context c, String key) {
        try {
            for (String line : read(new File(dir(c), "ttlink.conf"), 8192).split("\n")) {
                int i = line.indexOf('=');
                if (i > 0 && line.substring(0, i).trim().equals(key)) return line.substring(i + 1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static void saveConf(Context c, String tomtomKey) throws Exception {
        File f = new File(dir(c), "ttlink.conf");
        FileOutputStream o = new FileOutputStream(f);
        o.write(("# written by the TT Link app\ntomtom_key=" + tomtomKey.trim() + "\n").getBytes("UTF-8"));
        o.close();
        f.setReadable(true, false);
    }

    /** The last max bytes of a file. */
    static String read(File f, int max) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            long n = r.length();
            if (n == 0) { // /proc files report 0: read up to max
                byte[] b = new byte[max];
                FileInputStream in = new FileInputStream(f);
                int k = in.read(b);
                in.close();
                return k > 0 ? new String(b, 0, k, "UTF-8") : "";
            }
            long from = Math.max(0, n - max);
            byte[] b = new byte[(int) (n - from)];
            r.seek(from);
            r.readFully(b);
            return new String(b, "UTF-8");
        } finally {
            r.close();
        }
    }
}
