// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb.testapp;

import android.app.Application;

import io.github.muntashirakon.adb.PRNGFixes;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PRNGFixes.apply();
    }
}
