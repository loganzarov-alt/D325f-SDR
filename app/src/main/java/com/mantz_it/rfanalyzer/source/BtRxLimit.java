// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.app.AlertDialog;
import android.content.Context;
import com.mantz_it.rfanalyzer.automation.UiTranslations;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.EditText;

/* loaded from: classes3.dex */
public final class BtRxLimit {
    public static final long DEFAULT_HZ = 1000000000L;
    public static volatile long activeHz = DEFAULT_HZ;
    private static SharedPreferences prefs;


    public static void init(Context context) {
        prefs = context.getSharedPreferences("bt_rx_limit", 0);
        reload();
    }

    public static void reload() {
        SharedPreferences sharedPreferences = prefs;
        if (sharedPreferences != null) {
            activeHz = sharedPreferences.getLong("upper_hz", DEFAULT_HZ);
        }
    }

    public static void show(Context context) {
        EditText editText = new EditText(context);
        editText.setInputType(8194);
        editText.setHint(UiTranslations.text(context, "Upper limit, MHz"));
        SharedPreferences sharedPreferences = context.getSharedPreferences("bt_rx_limit", 0);
        editText.setText(Double.toString(sharedPreferences.getLong("upper_hz", DEFAULT_HZ) / 1000000.0d));
        editText.selectAll();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(UiTranslations.text(context, "BT receive limit"));
        builder.setMessage(UiTranslations.text(context, "Enter 1–2402 MHz (default 1000). Below this limit reception uses the BT oscillator; at or above it reception is direct. Stop and restart RX after saving."));
        builder.setView(editText);
        builder.setPositiveButton(UiTranslations.text(context, "Save"), new BtRxLimitSave(editText, sharedPreferences, context));
        builder.setNegativeButton(UiTranslations.text(context, "Cancel"), (DialogInterface.OnClickListener) null);
        builder.show();
    }
}
