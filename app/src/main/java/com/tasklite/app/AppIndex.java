package com.tasklite.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Package -> label lookup, built once on a background thread and swapped in atomically. */
final class AppIndex {
    volatile HashMap<String, String> label = new HashMap<>();
    volatile HashSet<String> user = new HashSet<>();

    static boolean isUserApp(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    void load(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> list = pm.getInstalledApplications(0);
        HashMap<String, String> l = new HashMap<>(list.size() * 2);
        HashSet<String> u = new HashSet<>();
        for (ApplicationInfo ai : list) {
            l.put(ai.packageName, String.valueOf(ai.loadLabel(pm)));
            if (isUserApp(ai)) u.add(ai.packageName);
        }
        label = l;
        user = u;
    }
}
