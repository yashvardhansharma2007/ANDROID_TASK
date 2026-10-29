package com.tasklite.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;

/**
 * Collects system statistics on low-priority background threads.
 * Runs ONLY while the Activity is visible (start()/stop()), so it costs nothing in the background.
 */
final class Sampler {
    interface Listener {
        void onSnapshot(Snapshot s);

        void onPro(ProResult r);
    }

    private final Context ctx;
    private final Listener listener;
    private final AppIndex index;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread wt = new HandlerThread("tl-sampler", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final HandlerThread pt = new HandlerThread("tl-pro", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker, proH;

    private volatile boolean running;
    private volatile long intervalMs = 1000;
    private volatile boolean procVisible;
    private volatile float proCpu = -1f;
    private volatile long proCpuAt;

    // reused buffers (no per-tick garbage for the hot reads)
    private final byte[] buf = new byte[4096];
    private final byte[] small = new byte[32];
    private final long[] vals = new long[10];
    private final long[] one = new long[1];

    // delta state (touched only on the sampler thread)
    private boolean statOk = true;
    private long prevTotal, prevIdle;
    private long prevRx = -1, prevTx = -1, prevNetAt;

    // CPU topology
    private int cores;
    private String[] curPath, maxPath;
    private int[] maxKhz;

    Sampler(Context app, Listener l, AppIndex index) {
        this.ctx = app;
        this.listener = l;
        this.index = index;
        wt.start();
        pt.start();
        worker = new Handler(wt.getLooper());
        proH = new Handler(pt.getLooper());
        initCores();
    }

    private void initCores() {
        int n = 0;
        String[] names = new File("/sys/devices/system/cpu").list();
        if (names != null) {
            for (String s : names) {
                if (s.matches("cpu\\d+")) n++;
            }
        }
        if (n <= 0) n = Runtime.getRuntime().availableProcessors();
        cores = n;
        curPath = new String[n];
        maxPath = new String[n];
        maxKhz = new int[n];
        for (int i = 0; i < n; i++) {
            curPath[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
            maxPath[i] = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq";
        }
    }

    int cores() {
        return cores;
    }

    void start() {
        if (running) return;
        running = true;
        prevTotal = 0;
        prevNetAt = 0;
        worker.post(tick);
        proH.post(proTick);
    }

    void stop() {
        running = false;
        worker.removeCallbacks(tick);
        proH.removeCallbacks(proTick);
    }

    void destroy() {
        stop();
        wt.quitSafely();
        pt.quitSafely();
    }

    void setIntervalMs(long ms) {
        intervalMs = ms;
    }

    void setProcessesVisible(boolean v) {
        if (procVisible == v) return;
        procVisible = v;
        if (v && running) recheckPro();
    }

    /** Forces an immediate Pro poll (used by the "Check again" button). */
    void recheckPro() {
        proH.removeCallbacks(proTick);
        proH.post(proTick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            final Snapshot s = collect();
            main.post(() -> listener.onSnapshot(s));
            worker.postDelayed(this, intervalMs);
        }
    };

    private final Runnable proTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            final ProResult r = Pro.poll(ctx, procVisible, index);
            if (r.state == Pro.ACTIVE && r.cpuTotal >= 0) {
                proCpu = r.cpuTotal;
                proCpuAt = SystemClock.elapsedRealtime();
            }
            main.post(() -> listener.onPro(r));
            long next = r.state == Pro.ACTIVE ? 5000 : (r.state == Pro.NEED_GRANT ? 4000 : 15000);
            proH.postDelayed(this, next);
        }
    };

    // ------------------------------------------------------------------ collection

    private Snapshot collect() {
        Snapshot s = new Snapshot();
        long now = SystemClock.elapsedRealtime();
        s.uptimeMs = now;
        readMemory(s);
        readCpu(s);
        readBattery(s);
        readStorage(s);
        readNet(s, now);
        return s;
    }

    private void readMemory(Snapshot s) {
        int n = ProcFs.read("/proc/meminfo", buf);
        if (n > 0) {
            String t = new String(buf, 0, n);
            s.memTotalKb = ProcFs.field(t, "MemTotal");
            s.memFreeKb = ProcFs.field(t, "MemFree");
            s.buffersKb = Math.max(0, ProcFs.field(t, "Buffers"));
            s.cachedKb = Math.max(0, ProcFs.field(t, "Cached"));
            s.swapTotalKb = Math.max(0, ProcFs.field(t, "SwapTotal"));
            s.swapFreeKb = Math.max(0, ProcFs.field(t, "SwapFree"));
            long avail = ProcFs.field(t, "MemAvailable");
            s.memAvailKb = avail >= 0 ? avail : s.memFreeKb + s.cachedKb + s.buffersKb;
        } else {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.memTotalKb = mi.totalMem / 1024;
            s.memAvailKb = mi.availMem / 1024;
            s.memFreeKb = s.memAvailKb;
        }
        n = ProcFs.read("/proc/loadavg", buf);
        if (n > 0) {
            String[] p = new String(buf, 0, n).trim().split(" ");
            if (p.length >= 3) {
                try {
                    s.load1 = Float.parseFloat(p[0]);
                    s.load5 = Float.parseFloat(p[1]);
                    s.load15 = Float.parseFloat(p[2]);
                    s.hasLoadAvg = true;
                } catch (NumberFormatException ignored) {
                    // leave hasLoadAvg false
                }
            }
        }
    }

    private void readCpu(Snapshot s) {
        s.cores = cores;
        // per-core frequency (also gives the fallback estimate)
        int[] mhz = new int[cores];
        float[] load = new float[cores];
        float sum = 0;
        int known = 0;
        for (int i = 0; i < cores; i++) {
            int cur = readKhz(curPath[i]);
            if (cur > 0 && maxKhz[i] == 0) {
                int mx = readKhz(maxPath[i]);
                maxKhz[i] = mx > 0 ? mx : -2; // -2 = unreadable, don't retry every tick
            }
            mhz[i] = cur > 0 ? cur / 1000 : -1;
            if (cur > 0 && maxKhz[i] > 0) {
                load[i] = Math.min(1f, cur / (float) maxKhz[i]);
                sum += load[i];
                known++;
            } else {
                load[i] = cur > 0 ? 0f : -1f;
            }
        }
        s.coreMhz = mhz;
        s.coreLoad = known > 0 ? load : null;

        // 1) real usage from /proc/stat (blocked for apps on Android 8+, works on some devices)
        if (statOk) {
            int n = ProcFs.read("/proc/stat", buf);
            if (n > 0) {
                int c = ProcFs.parseLongs(buf, n, 3, vals);
                long total = 0;
                for (int i = 0; i < Math.min(c, 8); i++) total += vals[i];
                long idle = vals[3] + (c > 4 ? vals[4] : 0);
                if (prevTotal > 0 && total > prevTotal) {
                    float pct = 100f * (1f - (idle - prevIdle) / (float) (total - prevTotal));
                    s.cpuPct = Math.max(0f, Math.min(100f, pct));
                    s.cpuSource = "Real usage from /proc/stat";
                }
                prevTotal = total;
                prevIdle = idle;
                if (s.cpuPct >= 0) return;
            } else {
                statOk = false;
            }
        }
        // 2) real usage from dumpsys (Pro mode)
        if (proCpu >= 0 && SystemClock.elapsedRealtime() - proCpuAt < 9000) {
            s.cpuPct = proCpu;
            s.cpuSource = "Real usage from dumpsys cpuinfo (Pro mode)";
            return;
        }
        // 3) estimate from how fast the cores are clocked
        if (known > 0) {
            s.cpuPct = 100f * sum / known;
            s.cpuSource = "Estimated from core clock speeds (Android blocks real CPU time for apps)";
            return;
        }
        s.cpuSource = "Not readable on this Android version. Enable Pro mode in the Processes tab.";
    }

    private int readKhz(String path) {
        int n = ProcFs.read(path, small);
        if (n <= 0) return -1;
        return ProcFs.parseLongs(small, n, 0, one) > 0 ? (int) one[0] : -1;
    }

    private void readBattery(Snapshot s) {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        s.battPct = level < 0 ? -1 : Math.round(100f * level / scale);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        s.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        s.battTempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        s.battMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        switch (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            case BatteryManager.BATTERY_HEALTH_GOOD: s.battHealth = "Good"; break;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: s.battHealth = "Overheat"; break;
            case BatteryManager.BATTERY_HEALTH_DEAD: s.battHealth = "Dead"; break;
            case BatteryManager.BATTERY_HEALTH_COLD: s.battHealth = "Cold"; break;
            default: s.battHealth = "";
        }
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int cur = Math.abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
            s.battMa = cur > 20000 ? cur / 1000 : cur; // some devices report uA, some mA
        }
    }

    private void readStorage(Snapshot s) {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            s.storageTotal = fs.getTotalBytes();
            s.storageFree = fs.getAvailableBytes();
        } catch (Exception ignored) {
            // leave zeros
        }
    }

    private void readNet(Snapshot s, long now) {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) return;
        if (prevNetAt > 0 && now > prevNetAt && prevRx >= 0) {
            float dt = (now - prevNetAt) / 1000f;
            s.rxBps = Math.max(0, (long) ((rx - prevRx) / dt));
            s.txBps = Math.max(0, (long) ((tx - prevTx) / dt));
        }
        prevRx = rx;
        prevTx = tx;
        prevNetAt = now;
    }
}
