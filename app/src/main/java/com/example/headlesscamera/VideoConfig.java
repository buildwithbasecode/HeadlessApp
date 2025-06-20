package com.example.headlesscamera;

import android.util.Log;

public class VideoConfig {
    private static final String TAG = "VideoConfig";

    // Default values
    public String resolution = "1080p";
    public int frameRate = 30;
    public String encoding = "H.264";
    public boolean audioEnabled = true;
    public int durationSeconds = 0; // 0 = record until manually stopped
    public boolean loopEnabled = false;
    public int intervalMinutes = 5;

    // Derived values (calculated from resolution)
    public int width = 1920;
    public int height = 1080;
    public int bitrate = 5000000; // 5 Mbps default

    public VideoConfig() {
        updateDimensionsFromResolution();
    }

    public void updateDimensionsFromResolution() {
        Log.d(TAG, "🔧 Updating dimensions for resolution: " + resolution);

        switch (resolution.toLowerCase()) {
            case "4k":
            case "2160p":
                width = 3840;
                height = 2160;
                bitrate = 20000000; // 20 Mbps for 4K
                break;
            case "1440p":
            case "2k":
                width = 2560;
                height = 1440;
                bitrate = 10000000; // 10 Mbps for 1440p
                break;
            case "1080p":
            case "fhd":
                width = 1920;
                height = 1080;
                bitrate = 5000000; // 5 Mbps for 1080p
                break;
            case "720p":
            case "hd":
                width = 1280;
                height = 720;
                bitrate = 3000000; // 3 Mbps for 720p
                break;
            case "480p":
                width = 854;
                height = 480;
                bitrate = 1500000; // 1.5 Mbps for 480p
                break;
            case "360p":
                width = 640;
                height = 360;
                bitrate = 1000000; // 1 Mbps for 360p
                break;
            default:
                Log.w(TAG, "⚠️ Unknown resolution '" + resolution + "', using 1080p defaults");
                width = 1920;
                height = 1080;
                bitrate = 5000000;
                resolution = "1080p";
                break;
        }

        Log.d(TAG, "📐 Resolution set to " + resolution + " (" + width + "x" + height + ") @ " + bitrate + " bps");
    }

    @Override
    public String toString() {
        return "VideoConfig{" +
                "resolution='" + resolution + '\'' +
                ", frameRate=" + frameRate +
                ", encoding='" + encoding + '\'' +
                ", audioEnabled=" + audioEnabled +
                ", durationSeconds=" + durationSeconds +
                ", loopEnabled=" + loopEnabled +
                ", intervalMinutes=" + intervalMinutes +
                ", width=" + width +
                ", height=" + height +
                ", bitrate=" + bitrate +
                '}';
    }
}