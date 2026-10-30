package com.solarized.firedown;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/** The one static TaskManager uses, with the real flags
 *  (BaseActivity.screenIntent: NEW_TASK | CLEAR_TOP | SINGLE_TOP). */
public abstract class BaseActivity {
    public static Intent screenIntent(Context context, Class<? extends Activity> screen) {
        return new Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
