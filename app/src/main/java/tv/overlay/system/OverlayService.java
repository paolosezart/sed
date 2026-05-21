package tv.overlay.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OverlayService extends Service {
    static final String ACTION_SHOW = "tv.overlay.SHOW";
    static final String ACTION_HIDE = "tv.overlay.HIDE";
    static final String ACTION_REFRESH = "tv.overlay.REFRESH";

    private static final String CHANNEL_ID = "overlay";
    private static final int NOTIFICATION_ID = 1;
    private static final long UPDATE_INTERVAL_MS = 3000L;
    private static final long ROOT_TIMEOUT_SECONDS = 4L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WindowManager windowManager;
    private TextView overlayView;
    private boolean visible;
    private boolean collecting;
    private long lastTotalCpu = -1L;
    private long lastIdleCpu = -1L;
    private String lastText = "Loading system data...";

    private final Runnable updateLoop = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (visible) {
                mainHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        }
    };

    static void sendCommand(Context context, String action) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            hideOverlay();
            stopSelf();
        } else if (ACTION_REFRESH.equals(action)) {
            if (!visible) {
                showOverlay();
            }
            refresh();
        } else {
            showOverlay();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void showOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }
        if (overlayView == null) {
            overlayView = new TextView(this);
            overlayView.setTextColor(Color.WHITE);
            overlayView.setTextSize(16f);
            overlayView.setLineSpacing(2f, 1.0f);
            overlayView.setPadding(18, 14, 18, 14);
            overlayView.setBackgroundColor(Color.argb(160, 0, 0, 0));
            overlayView.setText(lastText);
        }
        if (!visible) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 24;
            params.y = 24;
            windowManager.addView(overlayView, params);
            visible = true;
            mainHandler.removeCallbacks(updateLoop);
            updateLoop.run();
        }
    }

    private void hideOverlay() {
        mainHandler.removeCallbacks(updateLoop);
        if (visible && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        visible = false;
    }

    private int overlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void refresh() {
        if (collecting) {
            return;
        }
        collecting = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                SystemSnapshot snapshot = collectSnapshot();
                lastText = snapshot.format();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        collecting = false;
                        if (overlayView != null) {
                            overlayView.setText(lastText);
                        }
                    }
                });
            }
        });
    }

    private SystemSnapshot collectSnapshot() {
        RootResult root = runRootScript("/system/bin/free -h 2>/dev/null || free -h 2>/dev/null\ncat /proc/meminfo 2>/dev/null\ncat /proc/stat 2>/dev/null\ngetprop ro.product.cpu.abi 2>/dev/null\ngetprop ro.product.cpu.abilist 2>/dev/null\n");
        String output = root.output;
        CpuSample cpuSample = parseCpu(output);
        float cpuLoad = computeCpuLoad(cpuSample);
        MemoryInfo memoryInfo = parseMemory(output);
        String abi = firstNonEmpty(findLastPropertyLine(output), firstAbi());
        return new SystemSnapshot(Build.VERSION.RELEASE, Build.VERSION.SDK_INT, systemBits(), friendlyArch(abi), cpuLoad, memoryInfo, root.ok);
    }

    private RootResult runRootScript(String script) {
        File file = null;
        try {
            file = File.createTempFile("tv-overlay-root", ".sh", getCacheDir());
            FileOutputStream outputStream = new FileOutputStream(file);
            outputStream.write(script.getBytes(StandardCharsets.UTF_8));
            outputStream.close();
            file.setReadable(true, false);
            Process process = new ProcessBuilder("su", "0", "sh", file.getAbsolutePath()).redirectErrorStream(true).start();
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1L, TimeUnit.SECONDS);
                return new RootResult(false, readAll(process.getInputStream()) + "root command timed out");
            }
            String output = readAll(process.getInputStream());
            int exitCode = process.exitValue();
            return new RootResult(exitCode == 0, output);
        } catch (Exception e) {
            return new RootResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (file != null) {
                file.delete();
            }
        }
    }

    private String readAll(InputStream inputStream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private CpuSample parseCpu(String output) {
        String[] lines = output.split("\\n");
        for (String line : lines) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                long user = parseLong(parts, 1);
                long nice = parseLong(parts, 2);
                long system = parseLong(parts, 3);
                long idle = parseLong(parts, 4);
                long iowait = parseLong(parts, 5);
                long irq = parseLong(parts, 6);
                long softirq = parseLong(parts, 7);
                long steal = parseLong(parts, 8);
                long total = user + nice + system + idle + iowait + irq + softirq + steal;
                return new CpuSample(total, idle + iowait);
            }
        }
        return new CpuSample(-1L, -1L);
    }

    private float computeCpuLoad(CpuSample sample) {
        if (sample.total <= 0 || sample.idle < 0) {
            return -1f;
        }
        if (lastTotalCpu < 0) {
            lastTotalCpu = sample.total;
            lastIdleCpu = sample.idle;
            return 0f;
        }
        long totalDelta = sample.total - lastTotalCpu;
        long idleDelta = sample.idle - lastIdleCpu;
        lastTotalCpu = sample.total;
        lastIdleCpu = sample.idle;
        if (totalDelta <= 0) {
            return -1f;
        }
        return Math.max(0f, Math.min(100f, (totalDelta - idleDelta) * 100f / totalDelta));
    }

    private MemoryInfo parseMemory(String output) {
        MemoryInfo info = parseFree(output);
        if (info.hasRam()) {
            return info;
        }
        return parseMemInfo(output);
    }

    private MemoryInfo parseFree(String output) {
        MemoryInfo info = new MemoryInfo();
        String[] lines = output.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Mem:")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 4) {
                    info.ramTotal = parts[1];
                    info.ramUsed = parts[2];
                    info.ramFree = parts[3];
                    if (parts.length >= 7) {
                        info.ramAvailable = parts[6];
                    }
                }
            } else if (trimmed.startsWith("Swap:")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 4) {
                    info.swapTotal = parts[1];
                    info.swapUsed = parts[2];
                    info.swapFree = parts[3];
                }
            }
        }
        return info;
    }

    private MemoryInfo parseMemInfo(String output) {
        long memTotal = 0L;
        long memFree = 0L;
        long memAvailable = 0L;
        long swapTotal = 0L;
        long swapFree = 0L;
        String[] lines = output.split("\\n");
        for (String line : lines) {
            if (line.startsWith("MemTotal:")) memTotal = parseMemInfoKb(line);
            else if (line.startsWith("MemFree:")) memFree = parseMemInfoKb(line);
            else if (line.startsWith("MemAvailable:")) memAvailable = parseMemInfoKb(line);
            else if (line.startsWith("SwapTotal:")) swapTotal = parseMemInfoKb(line);
            else if (line.startsWith("SwapFree:")) swapFree = parseMemInfoKb(line);
        }
        MemoryInfo info = new MemoryInfo();
        info.ramTotal = humanKb(memTotal);
        info.ramFree = humanKb(memFree);
        info.ramAvailable = humanKb(memAvailable);
        info.ramUsed = humanKb(Math.max(0L, memTotal - (memAvailable > 0L ? memAvailable : memFree)));
        info.swapTotal = humanKb(swapTotal);
        info.swapFree = humanKb(swapFree);
        info.swapUsed = humanKb(Math.max(0L, swapTotal - swapFree));
        return info;
    }

    private long parseMemInfoKb(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length >= 2) {
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private long parseLong(String[] parts, int index) {
        if (index >= parts.length) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String humanKb(long kb) {
        if (kb <= 0L) {
            return "0B";
        }
        float mib = kb / 1024f;
        if (mib >= 1024f) {
            return String.format(Locale.US, "%.1fGi", mib / 1024f);
        }
        return String.format(Locale.US, "%.0fMi", mib);
    }

    private String firstAbi() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            return Build.SUPPORTED_ABIS[0];
        }
        return Build.CPU_ABI;
    }

    private String findLastPropertyLine(String output) {
        String result = "";
        String[] lines = output.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("abi") || trimmed.startsWith("arm") || trimmed.startsWith("x86")) {
                result = trimmed.split(",")[0];
            }
        }
        return result;
    }

    private String firstNonEmpty(String first, String second) {
        if (first != null && first.trim().length() > 0) {
            return first.trim();
        }
        return second == null ? "unknown" : second;
    }

    private String systemBits() {
        if (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0) {
            return "64-bit";
        }
        return "32-bit";
    }

    private String friendlyArch(String abi) {
        String normalized = abi == null ? "" : abi.toLowerCase(Locale.US);
        if (normalized.contains("armeabi-v7a")) return "ARMv7";
        if (normalized.contains("arm64-v8a")) return "ARM64-v8a";
        if (normalized.contains("x86_64")) return "x86_64";
        if (normalized.contains("x86")) return "x86";
        if (normalized.contains("armeabi")) return "ARM";
        return abi == null || abi.length() == 0 ? "unknown" : abi;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "TV System Overlay", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
                .setContentTitle("TV System Overlay")
                .setContentText("Listening for ADB broadcast commands")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private static final class RootResult {
        final boolean ok;
        final String output;

        RootResult(boolean ok, String output) {
            this.ok = ok;
            this.output = output == null ? "" : output;
        }
    }

    private static final class CpuSample {
        final long total;
        final long idle;

        CpuSample(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    private static final class MemoryInfo {
        String ramTotal = "unknown";
        String ramUsed = "unknown";
        String ramFree = "unknown";
        String ramAvailable = "unknown";
        String swapTotal = "unknown";
        String swapUsed = "unknown";
        String swapFree = "unknown";

        boolean hasRam() {
            return !"unknown".equals(ramTotal);
        }
    }

    private static final class SystemSnapshot {
        final String androidVersion;
        final int apiLevel;
        final String bits;
        final String arch;
        final float cpuLoad;
        final MemoryInfo memory;
        final boolean rootOk;

        SystemSnapshot(String androidVersion, int apiLevel, String bits, String arch, float cpuLoad, MemoryInfo memory, boolean rootOk) {
            this.androidVersion = androidVersion;
            this.apiLevel = apiLevel;
            this.bits = bits;
            this.arch = arch;
            this.cpuLoad = cpuLoad;
            this.memory = memory;
            this.rootOk = rootOk;
        }

        String format() {
            String cpu = cpuLoad >= 0f ? String.format(Locale.US, "%.1f%%", cpuLoad) : "unknown";
            return "Android: " + androidVersion + " / API " + apiLevel + "\n"
                    + "System: " + bits + ", " + arch + "\n"
                    + "CPU: " + cpu + "\n"
                    + "RAM: used " + memory.ramUsed + " / free " + memory.ramFree + " / total " + memory.ramTotal + "\n"
                    + "RAM avail: " + memory.ramAvailable + "\n"
                    + "SWAP: used " + memory.swapUsed + " / free " + memory.swapFree + " / total " + memory.swapTotal + "\n"
                    + "Root su 0 sh: " + (rootOk ? "OK" : "FAIL");
        }
    }
}
