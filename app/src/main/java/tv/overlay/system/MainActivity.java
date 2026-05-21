package tv.overlay.system;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("TV System Overlay\nGrant overlay permission, then use:\nadb shell am broadcast -a tv.overlay.SHOW\nadb shell am broadcast -a tv.overlay.HIDE\nadb shell am broadcast -a tv.overlay.REFRESH");
        view.setTextSize(18f);
        view.setPadding(32, 32, 32, 32);
        setContentView(view);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            OverlayService.sendCommand(this, OverlayService.ACTION_SHOW);
        } else {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            OverlayService.sendCommand(this, OverlayService.ACTION_SHOW);
        }
    }
}
