package cn.nodemon.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only launcher-held PendingIntents may invoke widget actions. */
public final class WidgetActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(MonitorWidget.ACTION_PINNED.equals(intent.getAction())&&WidgetPinning.complete(context,intent))return;MonitorWidget.handleAction(context,intent);}
}
