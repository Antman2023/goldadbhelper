package io.github.devicebridge.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/** The default uses the system configuration without applying an override. */
abstract class ThemedActivity extends Activity {
    static final int FOLLOW_SYSTEM = 0, LIGHT = 1, DARK = 2;
    private static final String APPEARANCE = "appearance";
    private int appliedAppearance;

    static int appearance(Context context) {
        int saved = context.getSharedPreferences("settings", MODE_PRIVATE)
                .getInt(APPEARANCE, FOLLOW_SYSTEM);
        return saved == LIGHT || saved == DARK ? saved : FOLLOW_SYSTEM;
    }

    void selectAppearance(int choice) {
        if (choice == appearance(this)) return;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt(APPEARANCE, choice).apply();
        recreate();
    }

    @Override protected void attachBaseContext(Context base) {
        appliedAppearance = appearance(base);
        if (appliedAppearance == FOLLOW_SYSTEM) {
            super.attachBaseContext(base);
            return;
        }
        Configuration override = new Configuration();
        override.fontScale = 0;
        override.uiMode = appliedAppearance == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        super.attachBaseContext(base.createConfigurationContext(override));
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (appliedAppearance != appearance(this) && !isFinishing() && canRecreateForAppearance()) recreate();
    }

    boolean canRecreateForAppearance() { return true; }
}
