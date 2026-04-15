package com.speedoverlay.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class OverlayService extends Service {

    public static boolean isRunning = false;

    private static final String CHANNEL_ID = "SpeedOverlayChannel";
    private static final int NOTIFICATION_ID = 1001;

    private static final int MIN_SIZE_DP = 80;
    private static final int MAX_SIZE_DP = 220;
    private static final int BASE_SIZE_DP = 110;

    private WindowManager windowManager;
    private View overlayView;
    private View resizeHandle;
    private WindowManager.LayoutParams layoutParams;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private TextView tvSpeed;
    private TextView tvUnit;

    // Drag state
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    // Resize state
    private int initialWidth, initialHeight;
    private float initialResizeTouchX, initialResizeTouchY;

    private float density;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        density = getResources().getDisplayMetrics().density;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        setupOverlay();
        startLocationUpdates();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * density);
    }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_speed, null);
        tvSpeed = overlayView.findViewById(R.id.tvSpeed);
        tvUnit = overlayView.findViewById(R.id.tvUnit);
        resizeHandle = overlayView.findViewById(R.id.resizeHandle);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int initialSizePx = dpToPx(BASE_SIZE_DP);

        layoutParams = new WindowManager.LayoutParams(
                initialSizePx,
                initialSizePx,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 50;
        layoutParams.y = 200;

        // ── Drag: main overlay body ────────────────────────────────────────
        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = layoutParams.x;
                    initialY = layoutParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                    layoutParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(overlayView, layoutParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        });

        // ── Resize: bottom-right corner handle ────────────────────────────
        resizeHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialWidth = layoutParams.width;
                    initialHeight = layoutParams.height;
                    initialResizeTouchX = event.getRawX();
                    initialResizeTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialResizeTouchX;
                    float dy = event.getRawY() - initialResizeTouchY;
                    float delta = (Math.abs(dx) >= Math.abs(dy)) ? dx : dy;
                    int newSize = (int) (initialWidth + delta);
                    newSize = Math.max(dpToPx(MIN_SIZE_DP), Math.min(dpToPx(MAX_SIZE_DP), newSize));

                    layoutParams.width = newSize;
                    layoutParams.height = newSize;

                    // Scale text proportionally with overlay size
                    float ratio = (float) newSize / dpToPx(BASE_SIZE_DP);
                    tvSpeed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38 * ratio);
                    tvUnit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11 * ratio);

                    windowManager.updateViewLayout(overlayView, layoutParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        });

        windowManager.addView(overlayView, layoutParams);
    }

    private void startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    float speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
                    updateSpeedDisplay(speedKmh);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private void updateSpeedDisplay(float speedKmh) {
        int speed = Math.round(speedKmh);
        tvSpeed.post(() -> {
            tvSpeed.setText(String.valueOf(speed));

            int bgRes;
            int speedColor;

            if (speed < 30) {
                bgRes = R.drawable.overlay_bg_white;
                speedColor = 0xFFFFFFFF; // white number
            } else if (speed < 50) {
                bgRes = R.drawable.overlay_bg_lightblue;
                speedColor = 0xFF90CAF9; // light blue number
            } else if (speed < 80) {
                bgRes = R.drawable.overlay_bg_green;
                speedColor = 0xFF66BB6A; // green number
            } else if (speed < 100) {
                bgRes = R.drawable.overlay_bg_yellow;
                speedColor = 0xFFFFD600; // yellow number
            } else if (speed < 130) {
                bgRes = R.drawable.overlay_bg_orange;
                speedColor = 0xFFFF9800; // orange number
            } else {
                bgRes = R.drawable.overlay_bg_red;
                speedColor = 0xFFFF5252; // red number
            }

            overlayView.setBackgroundResource(bgRes);
            tvSpeed.setTextColor(speedColor);
            tvUnit.setTextColor(0xFFFFFFFF); // km/h always white
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Speed Overlay", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows current driving speed as an overlay");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Speed Overlay Active")
                .setContentText("Showing your current speed on screen")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
