package com.example.headlesscamera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class CameraService extends Service {

    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final String TAG = "CameraService";

    public static final String ACTION_OPEN_CAMERA = "com.example.headlesscamera.OPEN_CAMERA";
    public static final String ACTION_START_RECORDING = "com.example.headlesscamera.START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.example.headlesscamera.STOP_RECORDING";
    public static final String ACTION_ENABLE_LOOPING = "com.example.headlesscamera.ENABLE_LOOPING";

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String cameraId;
    private boolean isRecording = false;
    private boolean isLooping = false;

    private PowerManager.WakeLock wakeLock;

    // Configuration-based recording
    private VideoConfig videoConfig;
    private Handler recordingHandler;
    private Runnable stopRecordingRunnable;
    private Runnable loopRecordingRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🏗️ === CameraService onCreate ===");

        // Load configuration
        videoConfig = ConfigParser.loadConfig(this);
        recordingHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "📋 ACTION_START_RECORDING = '" + ACTION_START_RECORDING + "'");
        Log.d(TAG, "📋 ACTION_STOP_RECORDING = '" + ACTION_STOP_RECORDING + "'");
        Log.d(TAG, "📋 ACTION_OPEN_CAMERA = '" + ACTION_OPEN_CAMERA + "'");
        Log.d(TAG, "📋 ACTION_ENABLE_LOOPING = '" + ACTION_ENABLE_LOOPING + "'");

        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadlessCamera::CameraWakeLock");
                wakeLock.acquire();
                Log.d(TAG, "🔋 WakeLock acquired");
            }

            createNotificationChannel();
            startForegroundService();

            // Test directory creation at startup
            File testDir = getOutputDirectory();
            Log.d(TAG, "📁 Startup directory test: " + (testDir != null ? testDir.getAbsolutePath() : "FAILED"));

            Log.d(TAG, "✅ Service created successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in onCreate: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");

        String action = intent != null ? intent.getAction() : null;
        String command = intent != null ? intent.getStringExtra("command") : null;

        Log.d(TAG, "Received action: " + action + ", command: " + command);

        if (ACTION_START_RECORDING.equals(action) || "start".equals(command)) {
            startCameraAndRecord();
        } else if (ACTION_OPEN_CAMERA.equals(action) || "open".equals(command)) {
            openCameraOnly();
        } else if (ACTION_STOP_RECORDING.equals(action) || "stop".equals(command)) {
            stopRecording();
        } else if (ACTION_ENABLE_LOOPING.equals(action) || "loop".equals(command)) {
            enableLooping();
        }

        return START_STICKY;
    }

    private void startForegroundService() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📹 Headless Camera Service")
                .setContentText("🔴 Ready to record (runs in background)")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        startForeground(1, notification);
    }

    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📹 Headless Camera Service")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, notification);
        }
    }

    private void scheduleDurationLimit() {
        if (videoConfig.durationSeconds > 0) {
            Log.d(TAG, "⏰ Scheduling auto-stop in " + videoConfig.durationSeconds + " seconds");

            stopRecordingRunnable = () -> {
                Log.d(TAG, "⏰ Duration limit reached, stopping recording");
                stopRecording();

                // If looping is enabled, schedule next recording
                if (videoConfig.loopEnabled && isLooping) {
                    scheduleLoopRecording();
                }
            };

            recordingHandler.postDelayed(stopRecordingRunnable, videoConfig.durationSeconds * 1000L);
        }
    }

    private void scheduleLoopRecording() {
        if (videoConfig.intervalMinutes > 0) {
            Log.d(TAG, "🔄 Scheduling next recording in " + videoConfig.intervalMinutes + " minutes");

            loopRecordingRunnable = () -> {
                Log.d(TAG, "🔄 Loop interval reached, starting next recording");
                if (isLooping && !isRecording) {
                    startCameraAndRecord();
                }
            };

            recordingHandler.postDelayed(loopRecordingRunnable, videoConfig.intervalMinutes * 60 * 1000L);
            updateNotification("⏸️ Loop: Next in " + videoConfig.intervalMinutes + "min");
        }
    }

    private void enableLooping() {
        isLooping = true;
        Log.d(TAG, "🔄 Looping enabled with " + videoConfig.intervalMinutes + "min intervals");
        updateNotification("🔄 Loop mode: " + videoConfig.intervalMinutes + "min intervals");
    }

    private void startCameraAndRecord() {
        Log.d(TAG, "📹 Starting camera and record request");

        if (!checkPermissions()) {
            Log.e(TAG, "❌ Permissions not granted");
            updateNotification("❌ Permissions missing");
            return;
        }

        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording");
            updateNotification("⚠️ Already recording");
            return;
        }

        updateNotification("🔄 Starting camera...");

        openCamera(() -> {
            startRecording();
            updateNotification("🔴 RECORDING... (background)");
        });
    }

    private void openCameraOnly() {
        Log.d(TAG, "📷 Opening camera only");

        if (!checkPermissions()) {
            Log.e(TAG, "❌ Permissions not granted");
            return;
        }

        openCamera(() -> {
            Log.d(TAG, "✅ Camera opened successfully");
            updateNotification("📷 Camera ready (background)");
        });
    }

    private boolean checkPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void openCamera(Runnable onSuccess) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Camera permission not granted");
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    Log.d(TAG, "✅ Camera opened successfully");
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "📷 Camera disconnected");
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "❌ Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    updateNotification("❌ Camera error: " + error);
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ CameraAccessException: " + e.getMessage());
            updateNotification("❌ Camera access failed");
        }
    }

    private void startRecording() {
        Log.d(TAG, "🔴 === startRecording() with CONFIG ===");

        if (cameraDevice == null) {
            Log.e(TAG, "❌ Camera is not opened - cannot start recording");
            updateNotification("❌ Camera not ready");
            return;
        }

        if (isRecording) {
            Log.w(TAG, "⚠️ Already recording - ignoring request");
            updateNotification("⚠️ Already recording");
            return;
        }

        Log.d(TAG, "🔄 Setting up MediaRecorder with config...");
        setupMediaRecorder();

        if (mediaRecorder == null) {
            Log.e(TAG, "❌ MediaRecorder setup failed");
            updateNotification("❌ Recorder setup failed");
            return;
        }

        try {
            Log.d(TAG, "🎬 Creating capture session...");
            cameraDevice.createCaptureSession(
                    Collections.singletonList(mediaRecorder.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "✅ Capture session configured");
                            captureSession = session;

                            try {
                                CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(mediaRecorder.getSurface());

                                Log.d(TAG, "🎯 Setting repeating request...");
                                session.setRepeatingRequest(builder.build(), null, null);

                                Log.d(TAG, "▶️ Starting MediaRecorder...");
                                mediaRecorder.start();

                                isRecording = true;
                                Log.d(TAG, "🎬✅ RECORDING STARTED with " + videoConfig.resolution + " @ " + videoConfig.frameRate + "fps");
                                updateNotification("🔴 REC " + videoConfig.resolution + " (" + videoConfig.durationSeconds + "s limit)");

                                // Schedule automatic stop based on config duration
                                scheduleDurationLimit();

                            } catch (CameraAccessException e) {
                                Log.e(TAG, "❌ CameraAccessException in session: " + e.getMessage());
                                updateNotification("❌ Camera error during recording");
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "❌ IllegalStateException starting recorder: " + e.getMessage());
                                updateNotification("❌ Recorder state error");
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Unexpected error starting recording: " + e.getMessage());
                                updateNotification("❌ Recording start failed");
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "❌ Camera capture session configuration failed");
                            updateNotification("❌ Camera config failed");
                        }
                    }, null);

        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ CameraAccessException creating session: " + e.getMessage());
            updateNotification("❌ Camera session failed");
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected error in startRecording: " + e.getMessage());
            updateNotification("❌ Start recording failed");
        }
    }

    private File getOutputDirectory() {
        Log.d(TAG, "📁 === Determining output directory ===");

        File outputDir = null;
        try {
            outputDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (outputDir != null) {
                Log.d(TAG, "📁 External files dir available: " + outputDir.getAbsolutePath());
                Log.d(TAG, "📁 External files exists: " + outputDir.exists());
                Log.d(TAG, "📁 External files writable: " + outputDir.canWrite());
                if (outputDir.exists() || outputDir.mkdirs()) {
                    Log.d(TAG, "✅ Using external files directory");
                    return outputDir;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ External files dir failed: " + e.getMessage());
        }
        Log.e(TAG, "❌ No usable directory found!");
        return null;
    }

    private void setupMediaRecorder() {
        Log.d(TAG, "🎬 === Setting up MediaRecorder with CONFIG ===");

        try {
            mediaRecorder = new MediaRecorder();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(this);
            }

            // Audio setup from config
            if (videoConfig.audioEnabled) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                Log.d(TAG, "🎤 Audio enabled from config");
            } else {
                Log.d(TAG, "🔇 Audio disabled from config");
            }

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            File outputDir = getOutputDirectory();
            if (outputDir == null) {
                Log.e(TAG, "❌ No output directory available");
                updateNotification("❌ Storage not available");
                return;
            }

            Log.d(TAG, "📁 Final output directory: " + outputDir.getAbsolutePath());

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File outputFile = new File(outputDir, "recording_" + timestamp + ".mp4");

            Log.d(TAG, "🎬 Output file: " + outputFile.getAbsolutePath());

            mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

            // Video encoder from config
            if ("H.265".equalsIgnoreCase(videoConfig.encoding) || "HEVC".equalsIgnoreCase(videoConfig.encoding)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
                    Log.d(TAG, "📹 Using H.265/HEVC encoder from config");
                } else {
                    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    Log.d(TAG, "📹 H.265 not supported, falling back to H.264");
                }
            } else {
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                Log.d(TAG, "📹 Using H.264 encoder from config");
            }

            // Audio encoder from config (only if audio enabled)
            if (videoConfig.audioEnabled) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }

            // Video settings from config
            mediaRecorder.setVideoEncodingBitRate(videoConfig.bitrate);
            mediaRecorder.setVideoFrameRate(videoConfig.frameRate);
            mediaRecorder.setVideoSize(videoConfig.width, videoConfig.height);

            Log.d(TAG, "🎥 Video config applied:");
            Log.d(TAG, "  📐 Resolution: " + videoConfig.width + "x" + videoConfig.height);
            Log.d(TAG, "  🎞️ Frame rate: " + videoConfig.frameRate + " fps");
            Log.d(TAG, "  💾 Bitrate: " + videoConfig.bitrate + " bps");
            Log.d(TAG, "  🎤 Audio: " + videoConfig.audioEnabled);
            Log.d(TAG, "  ⏱️ Duration limit: " + videoConfig.durationSeconds + " seconds");
            Log.d(TAG, "  🔄 Loop enabled: " + videoConfig.loopEnabled);

            mediaRecorder.setOnErrorListener((mr, what, extra) -> {
                Log.e(TAG, "❌ MediaRecorder error: what=" + what + ", extra=" + extra);
                updateNotification("❌ Recording error: " + what);
                isRecording = false;
                cancelScheduledTasks();
            });

            mediaRecorder.setOnInfoListener((mr, what, extra) -> {
                Log.i(TAG, "ℹ️ MediaRecorder info: what=" + what + ", extra=" + extra);
            });

            Log.d(TAG, "📋 Preparing MediaRecorder...");
            mediaRecorder.prepare();
            Log.d(TAG, "✅ MediaRecorder prepared successfully");

            // Save output path to status file in same directory
            try {
                File statusFile = new File(outputDir, "latest_output.txt");
                FileWriter writer = new FileWriter(statusFile, false);
                writer.write(outputFile.getAbsolutePath());
                writer.close();
                Log.d(TAG, "📄 Output path saved to: " + statusFile.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG, "⚠️ Could not save output path: " + e.getMessage());
            }

        } catch (IOException e) {
            Log.e(TAG, "❌ MediaRecorder prepare failed: " + e.getMessage(), e);
            updateNotification("❌ Setup failed: " + e.getMessage());
            cleanupMediaRecorder();
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected error in setupMediaRecorder: " + e.getMessage(), e);
            updateNotification("❌ Unexpected error");
            cleanupMediaRecorder();
        }
    }

    private void cleanupMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "⚠️ Error releasing MediaRecorder: " + e.getMessage());
            }
            mediaRecorder = null;
        }
    }

    private void cancelScheduledTasks() {
        if (stopRecordingRunnable != null) {
            recordingHandler.removeCallbacks(stopRecordingRunnable);
            stopRecordingRunnable = null;
            Log.d(TAG, "⏰ Cancelled duration limit timer");
        }

        if (loopRecordingRunnable != null) {
            recordingHandler.removeCallbacks(loopRecordingRunnable);
            loopRecordingRunnable = null;
            Log.d(TAG, "🔄 Cancelled loop timer");
        }
    }

    private void stopRecording() {
        Log.d(TAG, "⏹️ === Stop recording requested ===");

        // Cancel any pending tasks
        cancelScheduledTasks();

        if (!isRecording) {
            Log.w(TAG, "⚠️ Not currently recording");
            updateNotification("⚠️ Not recording");
            return;
        }

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }

            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }

            isRecording = false;
            Log.d(TAG, "✅ RECORDING STOPPED");

            String statusMsg = "⏹️ Stopped (" + videoConfig.resolution + ")";
            if (isLooping) {
                statusMsg += " - Loop: " + videoConfig.intervalMinutes + "min";
            }
            updateNotification(statusMsg);

            // If looping is enabled, schedule next recording
            if (isLooping && videoConfig.loopEnabled) {
                scheduleLoopRecording();
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error stopping recording: " + e.getMessage());
            updateNotification("❌ Error stopping recording");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🔥 Service destroyed - cleaning up");

        // Cancel all scheduled tasks
        cancelScheduledTasks();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        stopRecording();

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "📱 App removed from recent apps - service continues running");
        updateNotification("📱 App closed - service still running");

        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        startService(restartService);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Headless Camera Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.setDescription("Background camera recording service");
            serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}