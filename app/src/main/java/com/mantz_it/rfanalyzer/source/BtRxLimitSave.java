// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.Toast;

/* loaded from: classes3.dex */
public final class BtRxLimitSave implements DialogInterface.OnClickListener {
    private Context context;
    private EditText input;
    private SharedPreferences prefs;

    public BtRxLimitSave(EditText editText, SharedPreferences sharedPreferences, Context context) {
        this.input = editText;
        this.prefs = sharedPreferences;
        this.context = context;
    }

    @Override // android.content.DialogInterface.OnClickListener
    public void onClick(DialogInterface dialogInterface, int i) throws NumberFormatException {
        String str;
        double d = Double.NaN;
        try {
            d = Double.parseDouble(this.input.getText().toString().replace(',', '.'));
        } catch (NumberFormatException e) {
        }
        if (d < 1.0d || d > 2402.0d || Double.isNaN(d)) {
            str = "Введите число от 1 до 2402 МГц. Значение не изменено.";
        } else {
            long jRound = Math.round(d * 1000000.0d);
            SharedPreferences.Editor editorEdit = this.prefs.edit();
            editorEdit.putLong("upper_hz", jRound);
            str = editorEdit.commit() ? "Сохранено. Остановите и снова запустите RX." : "Не удалось сохранить настройку.";
        }
        Toast.makeText(this.context, str, 1).show();
    }
}
