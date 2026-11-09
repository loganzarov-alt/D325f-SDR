// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.automation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.LocalTextStyle

/** Localizes legacy labels without changing stored enum names or recording metadata. */
@Composable fun LocalizedText(
    text: String, modifier: Modifier=Modifier, color: Color=Color.Unspecified,
    fontSize: TextUnit=TextUnit.Unspecified, fontStyle: FontStyle?=null,
    fontWeight: FontWeight?=null, fontFamily: FontFamily?=null,
    letterSpacing: TextUnit=TextUnit.Unspecified, textDecoration: TextDecoration?=null,
    textAlign: TextAlign?=null, lineHeight: TextUnit=TextUnit.Unspecified,
    overflow: TextOverflow=TextOverflow.Clip, softWrap: Boolean=true,
    maxLines: Int=Int.MAX_VALUE, minLines: Int=1,
    onTextLayout: ((TextLayoutResult)->Unit)?=null, style: TextStyle=LocalTextStyle.current
) {
    androidx.compose.material3.Text(UiTranslations.text(LocalContext.current,text), modifier,color,fontSize,
        fontStyle,fontWeight,fontFamily,letterSpacing,textDecoration,textAlign,lineHeight,
        overflow,softWrap,maxLines,minLines,onTextLayout,style)
}

@Composable fun localString(id: Int, vararg args: Any): String = LocalContext.current.getString(id, *args)
