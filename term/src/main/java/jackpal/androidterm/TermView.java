/*
 * Copyright (C) 2012 Steven Luo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jackpal.androidterm;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

import androidx.preference.PreferenceManager;
import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.compat.AndroidCompat;
import jackpal.androidterm.util.TermSettings;

public class TermView extends EmulatorView {
    public TermView(Context context, TermSession session, DisplayMetrics metrics) {
        super(context, session, metrics);
    }

    public void updatePrefs(TermSettings settings, ColorScheme scheme) {
        if (scheme == null) {
            scheme = new ColorScheme(settings.getColorScheme());
        }

        setTextSize(settings.getFontSize());
        setTextLeading(settings.getFontLeading());
        if (AndroidCompat.SDK >= 19) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.getContext());
            setTextFont(sp.getString(TermPreferences.FONT_FILENAME, null));
        } else {
            setTextFont(settings.getFontFile());
        }
        setAmbiWidth(settings.getAmbiWidth());
        setHwAcceleration(settings.getHwAcceleration());
        setUseCookedIME(settings.useCookedIME());
        setUseDirectCookedIME(settings.useDirectCookedIME());
        setCursorColor(settings.getCursorColor());
        setColorScheme(scheme);
        setAltSendsEsc(settings.getAltSendsEscFlag());
        setSupport8bitMeta(settings.getAltUses8bitMSB());
        setIgnoreXoff(settings.getIgnoreXoff());
        setRestartInput(settings.getRestartIME());
        setControlKeyCode(settings.getControlKeyCode());
        setFnKeyCode(settings.getFnKeyCode());
        setTermType(settings.getTermType());
        setMouseTracking(settings.getMouseTrackingFlag());
        setPreIMEShortcutsAction(settings.getImeShortcutsAction());
        setViCooperativeMode(settings.getViCooperativeMode());
        setForceNormalInputModeToPhysicalKeyboard(settings.getForceNormalInputModeToPhysicalKeyboard());
        SyncFileObserver.setCloudStorageHashCheckMode(settings.getCloudStorageCheck());
    }

    public void updatePrefs(TermSettings settings) {
        updatePrefs(settings, null);
    }

    @Override
    public String toString() {
        return getClass().toString() + '(' + getTermSession() + ')';
    }
}
