package com.tasklite.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements Sampler.Listener {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String GRANT_CMD = "adb shell pm grant com.tasklite.app android.permission.DUMP";
    private static final String GRANT_CMD2 = "adb shell pm grant com.tasklite.app android.permission.PACKAGE_USAGE_STATS";
    private static final String GRANT_BOTH = GRANT_CMD + "\n" + GRANT_CMD2;
    private static final long[] INTERVALS = {1000, 2000, 5000};

    private final AppIndex index = new AppIndex();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Sampler sampler;

    private FrameLayout content;
    private final View[] pages = new View[4];
    private final TextView[] nav = new TextView[4];
    private int tab = 0;
    private int intervalIdx = 0;
    private Snapshot last;

    // dashboard
    private GraphView cpuGraph, ramGraph;
    private CoreBarsView coreBars;
    private TextView cpuSrc, ramDetail;
    private final TextView[] batt = new TextView[2], stor = new TextView[2], net = new TextView[2], up = new TextView[2];

    // processes
    private LinearLayout procSetup, procHeader;
    private TextView recentHeader, procEmpty;
    private RecentAdapter recentAdapter;
    private List<AppRow> recentRows = new ArrayList<>();
    private boolean recentLoading = false;
    private final HashMap<String, Long> ended = new HashMap<>();
    private TextView procSetupTitle, procSetupMsg, procSummary;
    private ListView procList;
    private ProcAdapter procAdapter;
    private ProResult lastPro;
    private int procSort = 0;

    // apps
    private LinearLayout usageCard;
    private TextView appsInfo, systemPill;
    private ListView appsList;
    private AppAdapter appsAdapter;
    private List<AppRow> appsAll = new ArrayList<>();
    private int appSort = 0;
    private boolean showSystem = false, appsLoading = false, appsLoaded = false, lastUsageAccess = false;

    // system
    private TextView sysDevice, sysPcb, sysThreads;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sampler = new Sampler(getApplicationContext(), this, index);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(MATCH, WRAP));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        pages[0] = buildDashboard();
        pages[1] = buildProcesses();
        pages[2] = buildApps();
        pages[3] = buildSystem();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(buildNav(), new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(root);

        bg.execute(() -> index.load(getApplicationContext()));
        showTab(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sampler.start();
        ui.postDelayed(recentLoop, 5000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastUsageAccess = Apps.hasUsageAccess(this);
        updateUsageCard();
        // coming back from another app: refresh so what you just opened shows up immediately
        if (tab == 2) loadApps();
        else if (tab == 1) loadRecent();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(recentLoop);
        sampler.stop(); // nothing runs while we are in the background
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sampler.destroy();
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (tab != 0) showTab(0);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ chrome

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.tv(this, 22, Ui.TEXT, true);
        title.setText("TaskLite");
        TextView sub = Ui.tv(this, 12, Ui.SUB, false);
        sub.setText(Build.MANUFACTURER + " " + Build.MODEL + "  |  Android " + Build.VERSION.RELEASE);
        left.addView(title);
        left.addView(sub);
        h.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1f));

        final TextView chip = Chips.pill(this, "Refresh 1s", false);
        chip.setOnClickListener(v -> {
            intervalIdx = (intervalIdx + 1) % INTERVALS.length;
            sampler.setIntervalMs(INTERVALS[intervalIdx]);
            chip.setText("Refresh " + (INTERVALS[intervalIdx] / 1000) + "s");
        });
        h.addView(chip);
        return h;
    }

    private View buildNav() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        box.addView(line, new LinearLayout.LayoutParams(MATCH, 1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Ui.NAV);
        String[] names = {"Dashboard", "Processes", "Apps", "System"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = Ui.tv(this, 13, Ui.SUB, true);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(15), 0, dp(15));
            t.setOnClickListener(v -> showTab(idx));
            row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
            nav[i] = t;
        }
        box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return box;
    }

    private void showTab(int i) {
        tab = i;
        for (int k = 0; k < 4; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            nav[k].setTextColor(k == i ? Ui.ACCENT : Ui.SUB);
        }
        sampler.setProcessesVisible(i == 1);
        if (i == 0 && last != null) bindDashboard(last);
        if (i == 1) {
            renderProcesses();
            loadRecent();
        }
        if (i == 2) {
            updateUsageCard();
            loadApps();
        }
        if (i == 3) updateSystem();
    }

    // ------------------------------------------------------------------ sampler callbacks (main thread)

    @Override
    public void onSnapshot(Snapshot s) {
        last = s;
        cpuGraph.push(s.cpuPct < 0 ? 0 : s.cpuPct, s.cpuPct < 0 ? "n/a" : Math.round(s.cpuPct) + "%");
        float used = s.memTotalKb > 0 ? 100f * (s.memTotalKb - s.memAvailKb) / s.memTotalKb : 0;
        ramGraph.push(used, Math.round(used) + "%");
        if (tab == 0) bindDashboard(s);
        if (tab == 3) updateSystem();
    }

    @Override
    public void onPro(ProResult r) {
        lastPro = r;
        if (tab == 1) renderProcesses();
    }

    // ------------------------------------------------------------------ dashboard

    private View buildDashboard() {
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(4), dp(12), dp(12));
        sv.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout cpu = Ui.card(this);
        cpuGraph = new GraphView(this, "CPU", Ui.ACCENT);
        cpu.addView(cpuGraph, new LinearLayout.LayoutParams(MATCH, dp(120)));
        coreBars = new CoreBarsView(this);
        cpu.addView(coreBars, top(MATCH, WRAP, 10));
        cpuSrc = Ui.tv(this, 11, Ui.SUB, false);
        cpu.addView(cpuSrc, top(MATCH, WRAP, 8));
        col.addView(cpu, top(MATCH, WRAP, 0));

        LinearLayout ram = Ui.card(this);
        ramGraph = new GraphView(this, "Memory in use", Ui.PURPLE);
        ram.addView(ramGraph, new LinearLayout.LayoutParams(MATCH, dp(120)));
        ramDetail = Ui.tv(this, 13, Ui.TEXT, false);
        ramDetail.setLineSpacing(0, 1.2f);
        ram.addView(ramDetail, top(MATCH, WRAP, 10));
        col.addView(ram, top(MATCH, WRAP, 10));

        LinearLayout r1 = new LinearLayout(this);
        r1.addView(statCard("Battery", batt), new LinearLayout.LayoutParams(0, MATCH, 1f));
        r1.addView(statCard("Storage", stor), left(0, MATCH, 1f, 10));
        col.addView(r1, top(MATCH, WRAP, 10));

        LinearLayout r2 = new LinearLayout(this);
        r2.addView(statCard("Network", net), new LinearLayout.LayoutParams(0, MATCH, 1f));
        r2.addView(statCard("Uptime", up), left(0, MATCH, 1f, 10));
        col.addView(r2, top(MATCH, WRAP, 10));
        return sv;
    }

    private LinearLayout statCard(String title, TextView[] out) {
        LinearLayout c = Ui.card(this);
        TextView t = Ui.tv(this, 12, Ui.SUB, false);
        t.setText(title);
        out[0] = Ui.tv(this, 22, Ui.TEXT, true);
        out[1] = Ui.tv(this, 12, Ui.SUB, false);
        c.addView(t);
        c.addView(out[0], top(WRAP, WRAP, 4));
        c.addView(out[1], top(WRAP, WRAP, 4));
        return c;
    }

    private void bindDashboard(Snapshot s) {
        coreBars.set(s.coreLoad);
        cpuSrc.setText(s.cpuSource + (s.hasLoadAvg
                ? String.format(Locale.US, "\nLoad average: %.2f  %.2f  %.2f", s.load1, s.load5, s.load15) : ""));

        long used = s.memTotalKb - s.memAvailKb;
        StringBuilder sb = new StringBuilder(160);
        sb.append("Used  ").append(Ui.kb(used)).append(" of ").append(Ui.kb(s.memTotalKb)).append('\n');
        sb.append("Available  ").append(Ui.kb(s.memAvailKb)).append("   |   Free  ").append(Ui.kb(s.memFreeKb)).append('\n');
        sb.append("Cached  ").append(Ui.kb(s.cachedKb + s.buffersKb));
        if (s.swapTotalKb > 0) {
            sb.append("   |   Swap  ").append(Ui.kb(s.swapTotalKb - s.swapFreeKb)).append(" / ").append(Ui.kb(s.swapTotalKb));
        }
        ramDetail.setText(sb);

        batt[0].setText(s.battPct < 0 ? "--" : s.battPct + "%");
        batt[0].setTextColor(s.battPct >= 0 && s.battPct < 20 && !s.charging ? Ui.RED : Ui.TEXT);
        batt[1].setText((s.charging ? "Charging" : "On battery") + "\n"
                + String.format(Locale.US, "%.1f C  |  %d mA", s.battTempC, s.battMa)
                + (s.battHealth.isEmpty() ? "" : "\n" + s.battHealth));

        long sUsed = s.storageTotal - s.storageFree;
        stor[0].setText(s.storageTotal > 0 ? Math.round(100f * sUsed / s.storageTotal) + "%" : "--");
        stor[1].setText(Ui.bytes(sUsed) + " used\n" + Ui.bytes(s.storageFree) + " free");

        net[0].setText(Ui.rate(s.rxBps));
        net[1].setText("Down\nUp  " + Ui.rate(s.txBps));

        up[0].setText(Ui.duration(s.uptimeMs));
        up[1].setText(s.cores + " cores\nSince last boot");
    }

    // ------------------------------------------------------------------ processes

    private View buildProcesses() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        procSetup = Ui.card(this);
        procSetupTitle = Ui.tv(this, 16, Ui.TEXT, true);
        procSetupMsg = Ui.tv(this, 13, Ui.SUB, false);
        procSetupMsg.setLineSpacing(0, 1.2f);
        LinearLayout btns = new LinearLayout(this);
        TextView how = Chips.pill(this, "Enable Pro mode", true);
        how.setOnClickListener(v -> showProHelp());
        TextView again = Chips.pill(this, "Check again", false);
        again.setOnClickListener(v -> {
            toast("Checking...");
            sampler.recheckPro();
        });
        btns.addView(how);
        btns.addView(again, left(WRAP, WRAP, 0, 8));
        procSetup.addView(procSetupTitle);
        procSetup.addView(procSetupMsg, top(MATCH, WRAP, 6));
        procSetup.addView(btns, top(WRAP, WRAP, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, WRAP);
        slp.setMargins(dp(12), dp(4), dp(12), dp(8));
        page.addView(procSetup, slp);

        recentHeader = Ui.tv(this, 13, Ui.SUB, false);
        recentHeader.setText("Recently used apps (last 6 hours)");
        recentHeader.setPadding(dp(16), dp(4), dp(16), dp(6));
        page.addView(recentHeader, new LinearLayout.LayoutParams(MATCH, WRAP));
        procEmpty = Ui.tv(this, 13, Ui.SUB, false);
        procEmpty.setPadding(dp(16), dp(8), dp(16), dp(8));
        page.addView(procEmpty, new LinearLayout.LayoutParams(MATCH, WRAP));

        procHeader = new LinearLayout(this);
        procHeader.setOrientation(LinearLayout.VERTICAL);
        procHeader.setPadding(dp(16), dp(4), dp(16), dp(8));
        procSummary = Ui.tv(this, 13, Ui.SUB, false);
        procHeader.addView(procSummary);
        Chips sort = new Chips(this, new String[]{"CPU", "Memory", "Name"}, 0, i -> {
            procSort = i;
            renderProcesses();
        });
        procHeader.addView(sort, top(WRAP, WRAP, 8));
        page.addView(procHeader, new LinearLayout.LayoutParams(MATCH, WRAP));

        procList = newList();
        procAdapter = new ProcAdapter();
        recentAdapter = new RecentAdapter();
        procList.setAdapter(procAdapter);
        procList.setOnItemClickListener((av, v, pos, id) -> {
            Object item = av.getItemAtPosition(pos);
            if (item instanceof ProcRow) showProcessDialog((ProcRow) item);
            else if (item instanceof AppRow) showAppDialog((AppRow) item);
        });
        page.addView(procList, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return page;
    }

    private void showProHelp() {
        String msg = "One-time setup. Needs a PC, or Android 11+ wireless debugging.\n\n"
                + "1. Settings > About phone > tap Build number 7 times\n"
                + "2. Developer options > USB debugging ON\n"
                + "3. Connect to the PC and allow debugging\n"
                + "4. Run both commands on the PC:\n\n" + GRANT_CMD + "\n" + GRANT_CMD2 + "\n\n"
                + "5. Come back here and tap Check again.";
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Enable Pro mode")
                .setMessage(msg)
                .setPositiveButton("Copy commands", (x, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("cmd", GRANT_BOTH));
                    toast("Commands copied");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private final Runnable recentLoop = new Runnable() {
        @Override
        public void run() {
            boolean pro = lastPro != null && lastPro.state == Pro.ACTIVE;
            if (tab == 1 && !pro) loadRecent();
            ui.postDelayed(this, 5000);
        }
    };

    private void loadRecent() {
        if (recentLoading) return;
        recentLoading = true;
        final Context app = getApplicationContext();
        bg.execute(() -> {
            final List<AppRow> rows = Apps.recent(app, index, 6L * 3600 * 1000);
            ui.post(() -> {
                recentRows = rows;
                recentLoading = false;
                if (tab == 1) renderProcesses();
            });
        });
    }

    private void renderProcesses() {
        ProResult r = lastPro;
        boolean pro = r != null && r.state == Pro.ACTIVE;
        if (!pro) {
            procSetup.setVisibility(View.VISIBLE);
            procHeader.setVisibility(View.GONE);
            recentHeader.setVisibility(View.VISIBLE);
            procList.setVisibility(View.VISIBLE);
            if (r == null) {
                procSetupTitle.setText("Checking Pro mode...");
                procSetupMsg.setText("Showing recently used apps meanwhile.");
            } else if (r.state == Pro.NEED_GRANT) {
                procSetupTitle.setText("Want exact CPU and RAM per process?");
                procSetupMsg.setText("Android hides other apps' processes from normal apps, so the list below "
                        + "shows recently used apps instead. Pro mode (one-time setup) unlocks the real list.");
            } else {
                procSetupTitle.setText("Pro mode is blocked on this phone");
                procSetupMsg.setText("\"" + r.message + "\"\nShowing recently used apps instead.");
            }
            if (procList.getAdapter() != recentAdapter) procList.setAdapter(recentAdapter);
            recentAdapter.set(recentRows);
            procEmpty.setVisibility(recentRows.isEmpty() ? View.VISIBLE : View.GONE);
            procEmpty.setText(Apps.hasUsageAccess(this)
                    ? "No apps used in the last 6 hours yet. Open an app, then come back here."
                    : "Allow Usage access (Apps tab > Open settings) to list recently used apps.");
            return;
        }
        procSetup.setVisibility(View.GONE);
        recentHeader.setVisibility(View.GONE);
        procEmpty.setVisibility(View.GONE);
        procHeader.setVisibility(View.VISIBLE);
        procList.setVisibility(View.VISIBLE);
        if (procList.getAdapter() != procAdapter) procList.setAdapter(procAdapter);

        ArrayList<ProcRow> rows = new ArrayList<>(r.rows);
        final int sort = procSort;
        final boolean limited = r.limited;
        Collections.sort(rows, new Comparator<ProcRow>() {
            @Override
            public int compare(ProcRow a, ProcRow b) {
                if (limited && sort != 2) {
                    if (a.rank != b.rank) return a.rank - b.rank;
                    return a.label.compareToIgnoreCase(b.label);
                }
                if (sort == 0) return Float.compare(b.cpu, a.cpu);
                if (sort == 1) return Long.compare(b.pssKb, a.pssKb);
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        procAdapter.set(rows, sort);
        if (limited) {
            procSummary.setText(rows.size() + " running processes, most important first.\n"
                    + "This phone hides per-process CPU and RAM from apps, so the status is shown instead.");
            return;
        }
        procSummary.setText(String.format(Locale.US, "%d processes  |  CPU %s  |  Load %s%s",
                rows.size(),
                r.cpuTotal >= 0 ? Math.round(r.cpuTotal) + "%" : "--",
                r.load1 >= 0 ? String.format(Locale.US, "%.2f", r.load1) : "--",
                r.memLoaded ? "" : "  |  loading memory..."));
    }

    private void showProcessDialog(final ProcRow p) {
        String msg = p.cpu < 0
                ? String.format(Locale.US, "PID %d\nProcess  %s\nStatus  %s", p.pid, p.name, p.importance)
                : String.format(Locale.US, "PID %d\nProcess  %s\nCPU  %.1f%%\nMemory (PSS)  %s",
                        p.pid, p.name, p.cpu, Ui.kb(p.pssKb));
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(p.label).setMessage(msg).setNegativeButton("Close", null);
        if (p.pkg != null && !p.pkg.equals(getPackageName())) {
            d.setPositiveButton("End task", (x, w) -> endTask(p.pkg, p.label));
            d.setNeutralButton("Force stop", (x, w) -> openAppInfo(p.pkg));
        } else if (p.pkg == null) {
            d.setMessage(msg + "\n\nSystem process: can't be ended from an app.");
        }
        d.show();
    }

    private final class RecentAdapter extends BaseAdapter {
        ArrayList<AppRow> rows = new ArrayList<>();

        void set(List<AppRow> r) {
            rows = new ArrayList<>(r);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            RowView r = v == null ? new RowView(MainActivity.this, true) : (RowView) v;
            AppRow a = rows.get(pos);
            Long endedAt = ended.get(a.pkg);
            boolean wasEnded = endedAt != null && endedAt >= a.lastUsed;
            r.icon.setImageDrawable(a.icon);
            r.title.setText(a.label);
            r.sub.setText(a.pkg);
            r.v1.setText(wasEnded ? "Ended" : (a.foreground ? "In use" : "Background"));
            r.v1.setTextColor(wasEnded ? Ui.SUB : (a.foreground ? Ui.GREEN : Ui.TEXT));
            r.v2.setText("Used " + Ui.ago(a.lastUsed));
            r.bar.setVisibility(View.GONE);
            return r;
        }
    }

    private final class ProcAdapter extends BaseAdapter {
        ArrayList<ProcRow> rows = new ArrayList<>();
        float maxCpu = 1, maxPss = 1;
        int sort;

        void set(ArrayList<ProcRow> r, int sort) {
            rows = r;
            this.sort = sort;
            maxCpu = 0.1f;
            maxPss = 1;
            for (ProcRow p : r) {
                if (p.cpu > maxCpu) maxCpu = p.cpu;
                if (p.pssKb > maxPss) maxPss = p.pssKb;
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return rows.get(i).pid; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            RowView r = v == null ? new RowView(MainActivity.this, false) : (RowView) v;
            ProcRow p = rows.get(pos);
            r.title.setText(p.label);
            r.sub.setText("PID " + p.pid + (p.pkg == null ? "  |  system" : ""));
            if (p.cpu < 0) { // limited mode: only the process state is known
                r.v1.setText(p.importance);
                r.v1.setTextColor(p.rank <= 2 ? Ui.GREEN : (p.rank >= 7 ? Ui.SUB : Ui.TEXT));
                r.v2.setText("");
                r.bar.setVisibility(View.GONE);
                return r;
            }
            r.bar.setVisibility(View.VISIBLE);
            r.v1.setText(String.format(Locale.US, "%.1f%%", p.cpu));
            r.v1.setTextColor(p.cpu > 25 ? Ui.RED : (p.cpu > 8 ? Ui.ORANGE : Ui.TEXT));
            r.v2.setText(p.pssKb >= 0 ? Ui.kb(p.pssKb) : "...");
            if (sort == 1) r.bar.set(p.pssKb < 0 ? 0 : p.pssKb / maxPss, Ui.PURPLE);
            else r.bar.set(p.cpu / maxCpu, Ui.ACCENT);
            return r;
        }
    }

    // ------------------------------------------------------------------ apps

    private View buildApps() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        usageCard = Ui.card(this);
        TextView t = Ui.tv(this, 16, Ui.TEXT, true);
        t.setText("Allow usage access");
        TextView m = Ui.tv(this, 13, Ui.SUB, false);
        m.setText("Needed for screen time and app sizes. Android asks you once in Settings: "
                + "find TaskLite and switch it on.");
        TextView go = Chips.pill(this, "Open settings", true);
        go.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                toast("Open Settings > Apps > Special access > Usage access");
            }
        });
        usageCard.addView(t);
        usageCard.addView(m, top(MATCH, WRAP, 6));
        usageCard.addView(go, top(WRAP, WRAP, 10));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(MATCH, WRAP);
        ulp.setMargins(dp(12), dp(4), dp(12), dp(4));
        page.addView(usageCard, ulp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(4), dp(16), dp(8));
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        Chips sort = new Chips(this, new String[]{"Recent", "Usage", "Size", "Name"}, 0, i -> {
            appSort = i;
            renderApps();
        });
        row1.addView(sort);
        controls.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        systemPill = Chips.pill(this, "System apps", false);
        systemPill.setOnClickListener(v -> {
            showSystem = !showSystem;
            Chips.setPill(systemPill, showSystem);
            loadApps();
        });
        TextView clean = Chips.pill(this, "Clean RAM", true);
        clean.setOnClickListener(v -> cleanRam());
        row2.addView(systemPill);
        row2.addView(clean, left(WRAP, WRAP, 0, 8));
        TextView refresh = Chips.pill(this, "Refresh", false);
        refresh.setOnClickListener(v -> loadApps());
        row2.addView(refresh, left(WRAP, WRAP, 0, 8));
        controls.addView(row2, top(WRAP, WRAP, 8));
        appsInfo = Ui.tv(this, 12, Ui.SUB, false);
        controls.addView(appsInfo, top(WRAP, WRAP, 8));
        page.addView(controls, new LinearLayout.LayoutParams(MATCH, WRAP));

        appsList = newList();
        appsAdapter = new AppAdapter();
        appsList.setAdapter(appsAdapter);
        appsList.setOnItemClickListener((p, v, pos, id) -> showAppDialog(appsAdapter.rows.get(pos)));
        page.addView(appsList, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return page;
    }

    private void updateUsageCard() {
        boolean ok = Apps.hasUsageAccess(this);
        usageCard.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void loadApps() {
        if (appsLoading) return;
        appsLoading = true;
        appsInfo.setText("Loading apps...");
        final boolean sys = showSystem;
        final Context app = getApplicationContext();
        bg.execute(() -> {
            final List<AppRow> rows = Apps.load(app, sys);
            index.load(app);
            ui.post(() -> {
                appsAll = rows;
                appsLoading = false;
                appsLoaded = true;
                renderApps();
            });
        });
    }

    private void renderApps() {
        final int sort = appSort;
        ArrayList<AppRow> rows = new ArrayList<>(appsAll);
        Collections.sort(rows, new Comparator<AppRow>() {
            @Override
            public int compare(AppRow a, AppRow b) {
                if (sort == 0) return Long.compare(b.lastUsed, a.lastUsed);
                if (sort == 1) return Long.compare(b.fgMs, a.fgMs);
                if (sort == 2) return Long.compare(b.sizeBytes, a.sizeBytes);
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        appsAdapter.set(rows, sort);
        appsInfo.setText(rows.size() + " apps");
    }

    private void showAppDialog(final AppRow a) {
        String msg = "Package  " + a.pkg + "\nToday  " + Ui.duration(a.fgMs)
                + "\nLast used  " + Ui.ago(a.lastUsed) + "\nSize  " + Ui.bytes(a.sizeBytes);
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label).setMessage(msg).setNegativeButton("Close", null);
        if (!a.pkg.equals(getPackageName())) {
            d.setPositiveButton("End task", (x, w) -> endTask(a.pkg, a.label));
            d.setNeutralButton("Force stop", (x, w) -> openAppInfo(a.pkg));
        }
        d.show();
    }

    private void cleanRam() {
        final long before = last == null ? 0 : last.memAvailKb;
        final Context app = getApplicationContext();
        toast("Cleaning...");
        bg.execute(() -> {
            final int n = Apps.cleanBackground(app, index);
            ui.postDelayed(() -> {
                long after = last == null ? 0 : last.memAvailKb;
                long gained = Math.max(0, after - before);
                toast("Asked " + n + " apps to stop. RAM freed: " + Ui.kb(gained));
            }, 1500);
        });
    }

    private final class AppAdapter extends BaseAdapter {
        ArrayList<AppRow> rows = new ArrayList<>();
        long maxFg = 1, maxSize = 1;
        int sort;

        void set(ArrayList<AppRow> r, int sort) {
            rows = r;
            this.sort = sort;
            maxFg = 1;
            maxSize = 1;
            for (AppRow a : r) {
                if (a.fgMs > maxFg) maxFg = a.fgMs;
                if (a.sizeBytes > maxSize) maxSize = a.sizeBytes;
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            RowView r = v == null ? new RowView(MainActivity.this, true) : (RowView) v;
            AppRow a = rows.get(pos);
            r.icon.setImageDrawable(a.icon);
            r.title.setText(a.label);
            r.sub.setText("Today " + Ui.duration(a.fgMs) + "  |  " + Ui.ago(a.lastUsed));
            r.v1.setText(Ui.bytes(a.sizeBytes));
            Long endedAt = ended.get(a.pkg);
            boolean wasEnded = endedAt != null && endedAt >= a.lastUsed;
            r.v2.setText(wasEnded ? "ended" : (a.system ? "system" : ""));
            if (sort == 2) r.bar.set(a.sizeBytes < 0 ? 0 : a.sizeBytes / (float) maxSize, Ui.PURPLE);
            else r.bar.set(a.fgMs / (float) maxFg, Ui.ACCENT);
            return r;
        }
    }

    // ------------------------------------------------------------------ system / PCB

    private View buildSystem() {
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(4), dp(12), dp(12));
        sv.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        sysDevice = mono();
        col.addView(sectionCard("Device and kernel", sysDevice), top(MATCH, WRAP, 0));
        sysPcb = mono();
        col.addView(sectionCard("Process Control Block (this app)", sysPcb), top(MATCH, WRAP, 10));
        sysThreads = mono();
        col.addView(sectionCard("Threads (busiest first)", sysThreads), top(MATCH, WRAP, 10));

        String kernel = System.getProperty("os.version");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-10s %s\n", "Model", Build.MANUFACTURER + " " + Build.MODEL));
        sb.append(String.format(Locale.US, "%-10s %s (API %d)\n", "Android", Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        sb.append(String.format(Locale.US, "%-10s %s\n", "Kernel", kernel == null ? "?" : kernel));
        sb.append(String.format(Locale.US, "%-10s %s\n", "ABI", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        sb.append(String.format(Locale.US, "%-10s %s\n", "Hardware", Build.HARDWARE));
        sb.append(String.format(Locale.US, "%-10s %d", "CPU cores", sampler.cores()));
        sysDevice.setText(sb);
        return sv;
    }

    private LinearLayout sectionCard(String title, TextView body) {
        LinearLayout c = Ui.card(this);
        TextView t = Ui.tv(this, 13, Ui.ACCENT, true);
        t.setText(title);
        c.addView(t);
        c.addView(body, top(MATCH, WRAP, 8));
        return c;
    }

    private TextView mono() {
        TextView t = Ui.tv(this, 12, Ui.TEXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private static String stateName(char c) {
        switch (c) {
            case 'R': return "R running";
            case 'S': return "S sleeping";
            case 'D': return "D disk wait";
            case 'Z': return "Z zombie";
            case 'T': return "T stopped";
            case 't': return "t trace stop";
            case 'X': return "X dead";
            default: return String.valueOf(c);
        }
    }

    /** Fields after the ')' of a /proc/<pid>/stat line (comm may contain spaces). */
    private static String[] statFields(String stat) {
        if (stat == null) return null;
        int rp = stat.lastIndexOf(')');
        if (rp < 0 || rp + 2 >= stat.length()) return null;
        return stat.substring(rp + 2).split(" ");
    }

    private void updateSystem() {
        long tick = 100;
        try {
            tick = Os.sysconf(OsConstants._SC_CLK_TCK);
        } catch (Exception ignored) {
            // default to 100 Hz
        }
        long msPerTick = 1000L / Math.max(1, tick);

        String status = ProcFs.readString("/proc/self/status", 4096);
        String[] f = statFields(ProcFs.readString("/proc/self/stat", 1024));
        StringBuilder sb = new StringBuilder(400);
        sb.append(String.format(Locale.US, "%-14s %d\n", "PID", android.os.Process.myPid()));
        if (f != null && f.length > 17) {
            sb.append(String.format(Locale.US, "%-14s %s\n", "Parent PID", f[1]));
            sb.append(String.format(Locale.US, "%-14s %s\n", "State", stateName(f[0].charAt(0))));
            sb.append(String.format(Locale.US, "%-14s %s / %s\n", "Priority/nice", f[15], f[16]));
            sb.append(String.format(Locale.US, "%-14s %s\n", "Threads", f[17]));
            sb.append(String.format(Locale.US, "%-14s user %d ms, kernel %d ms\n", "CPU time",
                    parseL(f[11]) * msPerTick, parseL(f[12]) * msPerTick));
        }
        sb.append(String.format(Locale.US, "%-14s %d\n", "UID", android.os.Process.myUid()));
        if (status != null) {
            sb.append(String.format(Locale.US, "%-14s %s\n", "Virtual size", Ui.kb(ProcFs.field(status, "VmSize"))));
            sb.append(String.format(Locale.US, "%-14s %s (peak %s)\n", "Resident RAM",
                    Ui.kb(ProcFs.field(status, "VmRSS")), Ui.kb(ProcFs.field(status, "VmHWM"))));
            sb.append(String.format(Locale.US, "%-14s voluntary %d, forced %d\n", "Ctx switches",
                    ProcFs.field(status, "voluntary_ctxt_switches"),
                    ProcFs.field(status, "nonvoluntary_ctxt_switches")));
        }
        String[] fds = new File("/proc/self/fd").list();
        sb.append(String.format(Locale.US, "%-14s %d", "Open files", fds == null ? -1 : fds.length));
        sysPcb.setText(sb);

        String[] tids = new File("/proc/self/task").list();
        if (tids == null) {
            sysThreads.setText("Thread list not readable");
            return;
        }
        final ArrayList<String[]> list = new ArrayList<>();
        for (String tid : tids) {
            String s = ProcFs.readString("/proc/self/task/" + tid + "/stat", 512);
            if (s == null) continue;
            int lp = s.indexOf('('), rp = s.lastIndexOf(')');
            String[] ff = statFields(s);
            if (lp < 0 || rp < lp || ff == null || ff.length < 13) continue;
            long cpu = (parseL(ff[11]) + parseL(ff[12])) * msPerTick;
            list.add(new String[]{tid, s.substring(lp + 1, rp), ff[0], String.valueOf(cpu)});
        }
        Collections.sort(list, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Long.compare(parseL(b[3]), parseL(a[3]));
            }
        });
        StringBuilder tb = new StringBuilder(600);
        tb.append(String.format(Locale.US, "%-7s %-16s %s %8s\n", "TID", "NAME", "S", "CPU"));
        for (int i = 0; i < Math.min(12, list.size()); i++) {
            String[] r = list.get(i);
            String name = r[1].length() > 16 ? r[1].substring(0, 16) : r[1];
            tb.append(String.format(Locale.US, "%-7s %-16s %s %6s ms\n", r[0], name, r[2], r[3]));
        }
        tb.append(list.size()).append(" threads total");
        sysThreads.setText(tb);
    }

    private static long parseL(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ actions / helpers

    private void endTask(String pkg, String label) {
        Apps.endTask(this, pkg);
        ended.put(pkg, System.currentTimeMillis());
        recentAdapter.notifyDataSetChanged();
        appsAdapter.notifyDataSetChanged();
        sampler.recheckPro();
        toast("End request sent for " + label + ". If it keeps running (open app, music, "
                + "or phone-maker restriction) use Force stop.");
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            toast("Can't open app info");
        }
    }

    private ListView newList() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setDividerHeight(0);
        l.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        l.setOverScrollMode(View.OVER_SCROLL_NEVER);
        l.setScrollingCacheEnabled(false);
        l.setCacheColorHint(Color.TRANSPARENT);
        return l;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private LinearLayout.LayoutParams top(int w, int h, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams left(int w, int h, float weight, int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.leftMargin = dp(leftDp);
        return lp;
    }
}
