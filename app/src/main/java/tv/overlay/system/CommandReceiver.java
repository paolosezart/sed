package tv.overlay.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        OverlayService.sendCommand(context, intent.getAction());
    }
}
