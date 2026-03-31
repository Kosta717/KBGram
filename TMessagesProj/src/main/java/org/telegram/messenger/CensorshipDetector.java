/*
 * KBGram - Censorship detection module.
 * Monitors connection quality and automatically switches to proxy when blocking is detected.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CensorshipDetector {

    private static final String TAG = "KBGram.Censorship";
    private static final String PREFS_NAME = "kbgram_censorship";

    // Telegram DC IP addresses for connectivity checks
    private static final String[][] DC_ADDRESSES = {
            {"149.154.175.50", "80"},    // DC1
            {"149.154.167.51", "443"},   // DC2
            {"149.154.175.100", "443"},  // DC3
            {"149.154.167.91", "443"},   // DC4
            {"91.108.56.100", "443"},    // DC5
    };

    // Detection thresholds
    private static final int CONNECTION_TIMEOUT_MS = 3000;
    private static final int SLOW_THRESHOLD_MS = 2000;
    private static final int CONSECUTIVE_FAILURES_FOR_CENSORSHIP = 2;
    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // State
    private static volatile boolean enabled = true;
    private static volatile boolean autoProxyEnabled = true;
    private static volatile boolean censorshipDetected = false;
    private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final AtomicBoolean checking = new AtomicBoolean(false);
    private static volatile long lastCheckTime = 0;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static CensorshipListener listener;

    public interface CensorshipListener {
        void onCensorshipDetected();
        void onDirectConnectionRestored();
        void onCheckComplete(boolean isCensored, long latencyMs);
    }

    public static void setListener(CensorshipListener l) {
        listener = l;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setAutoProxyEnabled(boolean value) {
        autoProxyEnabled = value;
        saveSettings();
    }

    public static boolean isAutoProxyEnabled() {
        return autoProxyEnabled;
    }

    public static boolean isCensorshipDetected() {
        return censorshipDetected;
    }

    /**
     * Check connectivity to Telegram DCs.
     * Runs asynchronously and notifies the listener.
     */
    public static void checkConnectivity() {
        if (!enabled || checking.getAndSet(true)) {
            return;
        }

        executor.submit(() -> {
            try {
                long startTime = SystemClock.elapsedRealtime();
                boolean anySuccess = false;
                long bestLatency = Long.MAX_VALUE;
                int failCount = 0;

                for (String[] dc : DC_ADDRESSES) {
                    try {
                        long dcStart = SystemClock.elapsedRealtime();
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress(dc[0], Integer.parseInt(dc[1])), CONNECTION_TIMEOUT_MS);
                        long latency = SystemClock.elapsedRealtime() - dcStart;
                        socket.close();

                        if (latency < bestLatency) {
                            bestLatency = latency;
                        }

                        if (latency < SLOW_THRESHOLD_MS) {
                            anySuccess = true;
                        }

                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d(TAG + ": DC " + dc[0] + ":" + dc[1] + " responded in " + latency + "ms");
                        }
                    } catch (Exception e) {
                        failCount++;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d(TAG + ": DC " + dc[0] + ":" + dc[1] + " failed: " + e.getMessage());
                        }
                    }
                }

                lastCheckTime = System.currentTimeMillis();

                if (failCount >= DC_ADDRESSES.length || (!anySuccess && bestLatency > SLOW_THRESHOLD_MS)) {
                    // All DCs failed or are very slow — censorship detected
                    int failures = consecutiveFailures.incrementAndGet();

                    if (failures >= CONSECUTIVE_FAILURES_FOR_CENSORSHIP) {
                        if (!censorshipDetected) {
                            censorshipDetected = true;
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d(TAG + ": Censorship DETECTED! " + failCount + "/" + DC_ADDRESSES.length + " DCs failed");
                            }

                            if (autoProxyEnabled) {
                                // Auto-enable proxy
                                ProxyFetcher.fetchAndApplyBestProxy();
                            }

                            if (listener != null) {
                                AndroidUtilities.runOnUIThread(() -> listener.onCensorshipDetected());
                            }
                        }
                    }
                } else {
                    // Connection is OK
                    consecutiveFailures.set(0);

                    if (censorshipDetected) {
                        censorshipDetected = false;
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d(TAG + ": Direct connection RESTORED. Best latency: " + bestLatency + "ms");
                        }
                        if (listener != null) {
                            final long lat = bestLatency;
                            AndroidUtilities.runOnUIThread(() -> listener.onDirectConnectionRestored());
                        }
                    }
                }

                if (listener != null) {
                    final boolean censored = censorshipDetected;
                    final long lat = bestLatency;
                    AndroidUtilities.runOnUIThread(() -> listener.onCheckComplete(censored, lat));
                }

            } finally {
                checking.set(false);
            }
        });
    }

    /**
     * Periodically check connection if enough time has passed.
     */
    public static void checkIfNeeded() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - lastCheckTime > CHECK_INTERVAL_MS) {
            checkConnectivity();
        }
    }

    /**
     * Get a human-readable status string.
     */
    public static String getStatusString() {
        if (!enabled) {
            return "Disabled";
        }
        if (checking.get()) {
            return "Checking...";
        }
        if (censorshipDetected) {
            return "Blocking detected — using proxy";
        }
        return "Direct connection OK";
    }

    /**
     * Load saved settings.
     */
    public static void loadSettings() {
        if (ApplicationLoader.applicationContext == null) return;
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean("enabled", true);
        autoProxyEnabled = prefs.getBoolean("autoProxy", true);
    }

    /**
     * Save settings.
     */
    public static void saveSettings() {
        if (ApplicationLoader.applicationContext == null) return;
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("enabled", enabled)
                .putBoolean("autoProxy", autoProxyEnabled)
                .apply();
    }

    /**
     * Reset detection state.
     */
    public static void reset() {
        censorshipDetected = false;
        consecutiveFailures.set(0);
        lastCheckTime = 0;
    }
}
