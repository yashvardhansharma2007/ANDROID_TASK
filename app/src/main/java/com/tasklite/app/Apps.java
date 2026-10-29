package com.tasklite.app;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;
import android.os.storage.StorageManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Everything the Apps tab needs: usage stats, app sizes, and "end task". */
final class Apps {
    /** Icons are expensive to resolve; both loaders run on the single background thread, so a plain map is safe. */
    private static final HashMap<String, Drawable> ICONS = new HashMap<>();

    private Apps() {}

    private static Drawable icon(PackageManager pm, ApplicationInfo ai) {
        Drawable d = ICONS.get(ai.packageName);
        if (d == null) {
            d = ai.loadIcon(pm);
            ICONS.put(ai.packageName, d);
        }
        return d;
    }

    @SuppressWarnings("deprecation")
    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int uid = android.os.Process.myUid();
        int mode = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Heavy: call from a background thread. */
    static List<AppRow> load(Context c, boolean includeSystem) {
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> infos = pm.getInstalledApplications(0);
        boolean usage = hasUsageAccess(c);

        Map<String, UsageStats> stats = Collections.emptyMap();
        StorageStatsManager ssm = null;
        if (usage) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(
                    cal.getTimeInMillis(), System.currentTimeMillis());
            if (m != null) stats = m;
            ssm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
        }
        UserHandle me = android.os.Process.myUserHandle();

        ArrayList<AppRow> out = new ArrayList<>(infos.size());
        for (ApplicationInfo ai : infos) {
            boolean userApp = AppIndex.isUserApp(ai);
            if (!userApp && !includeSystem) continue;
            AppRow r = new AppRow();
            r.pkg = ai.packageName;
            r.system = !userApp;
            r.label = String.valueOf(ai.loadLabel(pm));
            r.icon = icon(pm, ai);
            UsageStats us = stats.get(ai.packageName);
            if (us != null) {
                r.fgMs = us.getTotalTimeInForeground();
                r.lastUsed = us.getLastTimeUsed();
            }
            if (ssm != null) {
                try {
                    StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, me);
                    r.sizeBytes = st.getAppBytes() + st.getDataBytes() + st.getCacheBytes();
                } catch (Exception ignored) {
                    // package vanished or stats not available: leave size unknown
                }
            }
            out.add(r);
        }
        return out;
    }


    /**
     * Apps that were opened in the last windowMs. Android keeps such apps in memory, so this is the best
     * "what is probably running" list an ordinary app can get without Pro mode.
     */
    @SuppressWarnings("deprecation")
    static List<AppRow> recent(Context c, AppIndex index, long windowMs) {
        ArrayList<AppRow> out = new ArrayList<>();
        if (!hasUsageAccess(c)) return out;
        if (index.label.isEmpty()) index.load(c);
        PackageManager pm = c.getPackageManager();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - windowMs, now);
        if (ev == null) return out;
        HashMap<String, long[]> last = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            if (t != UsageEvents.Event.MOVE_TO_FOREGROUND && t != UsageEvents.Event.MOVE_TO_BACKGROUND) continue;
            String pkg = e.getPackageName();
            long[] st = last.get(pkg);
            if (st == null) {
                st = new long[2];
                last.put(pkg, st);
            }
            st[0] = e.getTimeStamp();
            st[1] = t == UsageEvents.Event.MOVE_TO_FOREGROUND ? 1 : 0;
        }
        String self = c.getPackageName();
        for (Map.Entry<String, long[]> en : last.entrySet()) {
            String pkg = en.getKey();
            if (pkg.equals(self) || pm.getLaunchIntentForPackage(pkg) == null) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                AppRow r = new AppRow();
                r.pkg = pkg;
                String lab = index.label.get(pkg);
                r.label = lab != null ? lab : String.valueOf(ai.loadLabel(pm));
                r.lastUsed = en.getValue()[0];
                r.foreground = en.getValue()[1] == 1;
                r.system = !AppIndex.isUserApp(ai);
                r.icon = icon(pm, ai);
                out.add(r);
            } catch (PackageManager.NameNotFoundException ignored) {
                // uninstalled since the event
            }
        }
        Collections.sort(out, new Comparator<AppRow>() {
            @Override
            public int compare(AppRow a, AppRow b) {
                return Long.compare(b.lastUsed, a.lastUsed);
            }
        });
        return out;
    }

    /** Asks the kernel's low-level process manager to end cached/background processes of one package. */
    static void endTask(Context c, String pkg) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        am.killBackgroundProcesses(pkg);
    }

    /** "Clean RAM": ends background processes of every user app except us and the launcher. */
    static int cleanBackground(Context c, AppIndex index) {
        PackageManager pm = c.getPackageManager();
        if (index.user.isEmpty()) index.load(c);
        String home = "";
        ResolveInfo ri = pm.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null) home = ri.activityInfo.packageName;
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        HashSet<String> users = index.user;
        int n = 0;
        for (String p : users) {
            if (p.equals(c.getPackageName()) || p.equals(home)) continue;
            am.killBackgroundProcesses(p);
            n++;
        }
        return n;
    }
}
