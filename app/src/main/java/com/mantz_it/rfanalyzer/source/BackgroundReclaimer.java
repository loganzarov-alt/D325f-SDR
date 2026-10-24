// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/** One-shot reclamation using Android's process importance rules, never SIGSTOP. */
public final class BackgroundReclaimer {
    private BackgroundReclaimer() {}
    public static String reclaim(Context context) {
        if (Build.VERSION.SDK_INT >= 34)
            return "Android 14+: очистка других приложений через этот API недоступна";
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return "Очистка RAM недоступна";
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(before);
        String keyboard = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String keyboardPackage = keyboard == null ? "" : keyboard.split("/")[0];
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        String homePackage = home == null || home.activityInfo == null ? "" : home.activityInfo.packageName;
        String accessibility = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        int requested = 0;
        for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(0)) {
            String name = app.packageName;
            // Preserve core/shared-system UIDs and interaction/root dependencies.
            if (app.uid < 10000 || app.uid == context.getApplicationInfo().uid ||
                    name.equals(keyboardPackage) || name.equals(homePackage) ||
                    name.equals("com.android.systemui") || name.contains("supersu") ||
                    name.contains("superuser") || name.contains("magisk") ||
                    (accessibility != null && (":" + accessibility).contains(":" + name + "/"))) continue;
            try {
                // Android selects background processes; visible/bound essential
                // processes survive. Packages are not disabled or force-stopped.
                manager.killBackgroundProcesses(name);
                requested++;
            } catch (SecurityException ignored) { }
        }
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(after);
        return "Доступная RAM: " + before.availMem / 1048576 + " → " + after.availMem / 1048576 +
                " МБ; запрос очистки: " + requested + " приложений";
    }
}
