package com.tasklite.app;

import android.graphics.drawable.Drawable;

import java.util.ArrayList;

/** One immutable-ish reading of the whole device, produced on the sampler thread. */
final class Snapshot {
    long memTotalKb, memAvailKb, memFreeKb, cachedKb, buffersKb, swapTotalKb, swapFreeKb;
    float cpuPct = -1f;            // 0..100, -1 = not available
    String cpuSource = "";
    int cores;
    int[] coreMhz;                 // current MHz per core, -1 = offline / unreadable
    float[] coreLoad;              // cur/max frequency per core 0..1, -1 = unknown
    boolean hasLoadAvg;
    float load1, load5, load15;
    int battPct = -1;
    boolean charging;
    float battTempC;
    int battMv, battMa;
    String battHealth = "";
    long storageTotal, storageFree;
    long rxBps = -1, txBps = -1;
    long uptimeMs;
}

/** One row of the process list (Pro mode). */
final class ProcRow {
    int pid;
    String name;       // raw process name, e.g. com.android.chrome:sandboxed_process0
    String label;      // friendly name
    String pkg;        // owning package, or null for native / system processes
    float cpu;         // percent of total CPU
    long pssKb = -1;   // proportional set size in kB
    String importance = ""; // Foreground / Visible / Service / Cached ... (limited mode)
    int rank = 99;           // lower = more important
}

/** Result of one Pro-mode poll. */
final class ProResult {
    int state;                         // Pro.NEED_GRANT / ACTIVE / ERROR
    String message = "";
    ArrayList<ProcRow> rows = new ArrayList<>();
    float cpuTotal = -1f;
    float load1 = -1f;
    boolean memLoaded;
    boolean limited;                   // true = only process list + importance, no CPU/RAM
}

/** One installed app in the Apps tab. */
final class AppRow {
    String pkg, label;
    long fgMs;            // foreground time today
    long lastUsed;        // epoch ms, 0 = never / unknown
    long sizeBytes = -1;  // app + data + cache
    boolean system;
    boolean foreground;   // last usage event was 'moved to foreground'
    Drawable icon;
}
