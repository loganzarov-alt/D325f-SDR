// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.content.Context;
import com.mantz_it.rfanalyzer.automation.UiTranslations;
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
            str = "Enter a number from 1 to 2402 MHz. The setting was not changed.";
        } else {
            long jRound = Math.round(d * 1000000.0d);
            SharedPreferences.Editor editorEdit = this.prefs.edit();
            editorEdit.putLong("upper_hz", jRound);
            str = editorEdit.commit() ? "Saved. Stop and restart RX." : "Could not save the setting.";
        }
        Toast.makeText(this.context, UiTranslations.text(this.context, str), 1).show();
    }
}
