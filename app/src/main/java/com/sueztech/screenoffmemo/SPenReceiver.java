package com.sueztech.screenoffmemo;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SPenReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (!intent.getBooleanExtra("penInsert", true)) {
            if (((KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE))
                    .isKeyguardLocked()) {
                Intent memoIntent = new Intent(context, MemoActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(memoIntent);
            }
        }

    }
}
