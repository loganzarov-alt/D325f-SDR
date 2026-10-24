// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.EditText;

/* loaded from: classes3.dex */
public final class BtRxLimit {
    public static volatile long activeHz = 800000000;
    private static SharedPreferences prefs;


    public static void init(Context context) {
        prefs = context.getSharedPreferences("bt_rx_limit", 0);
        reload();
    }

    public static void reload() {
        SharedPreferences sharedPreferences = prefs;
        if (sharedPreferences != null) {
            activeHz = sharedPreferences.getLong("upper_hz", 800000000L);
        }
    }

    public static void show(Context context) {
        EditText editText = new EditText(context);
        editText.setInputType(8194);
        editText.setHint("Верхняя граница, МГц");
        SharedPreferences sharedPreferences = context.getSharedPreferences("bt_rx_limit", 0);
        editText.setText(Double.toString(sharedPreferences.getLong("upper_hz", 800000000L) / 1000000.0d));
        editText.selectAll();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Граница приёма с BT-гетеродином");
        builder.setMessage("Введите 1–2402 МГц (по умолчанию 800). Ниже границы — с гетеродином, на границе и выше — напрямую. После сохранения остановите и снова запустите RX.");
        builder.setView(editText);
        builder.setPositiveButton("Сохранить", new BtRxLimitSave(editText, sharedPreferences, context));
        builder.setNegativeButton("Отмена", (DialogInterface.OnClickListener) null);
        builder.show();
    }
}
