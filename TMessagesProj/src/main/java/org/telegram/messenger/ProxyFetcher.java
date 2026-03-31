/*
 * KBGram - Automatic proxy fetcher and manager.
 * Downloads, tests, and applies working MTProto/SOCKS5 proxies.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProxyFetcher {

    private static final String TAG = "KBGram.ProxyFetcher";
    private static final String PREFS_NAME = "kbgram_proxies";

    // URLs to fetch proxy lists from (MTProto proxies)
    // These can be updated to point to your own proxy list server
    private static final String[] PROXY_LIST_URLS = {
            "https://raw.githubusercontent.com/hookzof/socks5_list/master/tg/mtproto.json",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
    };

    // Hardcoded fallback MTProto proxies
    private static final ProxyInfo[] FALLBACK_PROXIES = {
            new ProxyInfo("proxy.mtproto.co", 443, "7a35e76f616e6f6e796d6f757300"),
            new ProxyInfo("mtproto.freeproxy.ninja", 443, "dd00000000000000000000000000000000"),
            new ProxyInfo("proxy.digitalresistance.dog", 443, "d41d8cd98f00b204e9800998ecf8427e"),
    };

    private static final int PROXY_TEST_TIMEOUT_MS = 5000;
    private static final int MAX_PROXIES_TO_TEST = 20;
    private static final long REFRESH_INTERVAL_MS = 60 * 60 * 1000; // 1 hour

    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final AtomicBoolean fetching = new AtomicBoolean(false);
    private static volatile long lastFetchTime = 0;

    private static final List<ProxyInfo> cachedProxies = Collections.synchronizedList(new ArrayList<>());
    private static ProxyFetchListener fetchListener;

    public interface ProxyFetchListener {
        void onProxiesFetched(List<ProxyInfo> proxies);
        void onProxyApplied(ProxyInfo proxy);
        void onFetchFailed(String error);
    }

    public static void setFetchListener(ProxyFetchListener listener) {
        fetchListener = listener;
    }

    /**
     * Fetch proxies and apply the best one automatically.
     */
    public static void fetchAndApplyBestProxy() {
        if (fetching.getAndSet(true)) {
            return;
        }

        executor.submit(() -> {
            try {
                List<ProxyInfo> proxies = fetchProxies();

                if (proxies.isEmpty()) {
                    // Use fallback proxies
                    for (ProxyInfo fallback : FALLBACK_PROXIES) {
                        proxies.add(fallback);
                    }
                }

                if (proxies.isEmpty()) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d(TAG + ": No proxies available");
                    }
                    if (fetchListener != null) {
                        AndroidUtilities.runOnUIThread(() -> fetchListener.onFetchFailed("No proxies found"));
                    }
                    return;
                }

                // Test proxies and find the best one
                ProxyInfo bestProxy = testAndRankProxies(proxies);

                if (bestProxy != null) {
                    // Apply the proxy
                    applyProxy(bestProxy);

                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d(TAG + ": Applied proxy " + bestProxy.host + ":" + bestProxy.port +
                                " (latency: " + bestProxy.latency + "ms)");
                    }

                    if (fetchListener != null) {
                        AndroidUtilities.runOnUIThread(() -> fetchListener.onProxyApplied(bestProxy));
                    }
                }

                cachedProxies.clear();
                cachedProxies.addAll(proxies);
                lastFetchTime = System.currentTimeMillis();

                if (fetchListener != null) {
                    final List<ProxyInfo> finalProxies = new ArrayList<>(proxies);
                    AndroidUtilities.runOnUIThread(() -> fetchListener.onProxiesFetched(finalProxies));
                }

            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(TAG + ": Fetch failed", e);
                }
                if (fetchListener != null) {
                    AndroidUtilities.runOnUIThread(() -> fetchListener.onFetchFailed(e.getMessage()));
                }
            } finally {
                fetching.set(false);
            }
        });
    }

    /**
     * Fetch proxy lists from remote URLs.
     */
    private static List<ProxyInfo> fetchProxies() {
        List<ProxyInfo> proxies = new ArrayList<>();

        for (String url : PROXY_LIST_URLS) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null && count < MAX_PROXIES_TO_TEST) {
                        ProxyInfo proxy = parseProxyLine(line.trim());
                        if (proxy != null) {
                            proxies.add(proxy);
                            count++;
                        }
                    }
                    reader.close();
                }
                conn.disconnect();

                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + ": Fetched " + proxies.size() + " proxies from " + url);
                }
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(TAG + ": Failed to fetch from " + url, e);
                }
            }
        }

        // Also load saved proxies
        List<ProxyInfo> saved = loadSavedProxies();
        proxies.addAll(saved);

        return proxies;
    }

    /**
     * Parse a proxy line in format "host:port", "host:port:secret", or tg://proxy?... URL
     */
    private static ProxyInfo parseProxyLine(String line) {
        if (TextUtils.isEmpty(line) || line.startsWith("#")) {
            return null;
        }

        // Handle tg://proxy?server=...&port=...&secret=... format
        if (line.startsWith("tg://proxy?") || line.startsWith("https://t.me/proxy?")) {
            return parseTgProxyUrl(line);
        }

        String[] parts = line.split(":");
        if (parts.length >= 2) {
            try {
                String host = parts[0].trim();
                int port = Integer.parseInt(parts[1].trim());
                String secret = parts.length >= 3 ? parts[2].trim() : "";

                if (!TextUtils.isEmpty(host) && port > 0 && port <= 65535) {
                    return new ProxyInfo(host, port, secret);
                }
            } catch (NumberFormatException e) {
                // Invalid port
            }
        }
        return null;
    }

    /**
     * Parse a tg://proxy?server=...&port=...&secret=... URL
     */
    private static ProxyInfo parseTgProxyUrl(String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String server = uri.getQueryParameter("server");
            String portStr = uri.getQueryParameter("port");
            String secret = uri.getQueryParameter("secret");

            if (!TextUtils.isEmpty(server) && !TextUtils.isEmpty(portStr)) {
                int port = Integer.parseInt(portStr);
                if (port > 0 && port <= 65535) {
                    return new ProxyInfo(server, port, secret != null ? secret : "");
                }
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(TAG + ": Failed to parse tg proxy URL: " + url, e);
            }
        }
        return null;
    }

    /**
     * Test proxies and return the best one by latency.
     */
    private static ProxyInfo testAndRankProxies(List<ProxyInfo> proxies) {
        ProxyInfo best = null;
        long bestLatency = Long.MAX_VALUE;

        for (ProxyInfo proxy : proxies) {
            try {
                long start = SystemClock.elapsedRealtime();
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(proxy.host, proxy.port), PROXY_TEST_TIMEOUT_MS);
                long latency = SystemClock.elapsedRealtime() - start;
                socket.close();

                proxy.latency = latency;
                proxy.available = true;

                if (latency < bestLatency) {
                    bestLatency = latency;
                    best = proxy;
                }

                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + ": Proxy " + proxy.host + ":" + proxy.port + " OK, latency: " + latency + "ms");
                }
            } catch (Exception e) {
                proxy.available = false;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + ": Proxy " + proxy.host + ":" + proxy.port + " FAILED");
                }
            }
        }

        return best;
    }

    /**
     * Apply a proxy to Telegram's SharedConfig.
     */
    private static void applyProxy(ProxyInfo proxy) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SharedConfig.ProxyInfo proxyInfo;

                if (!TextUtils.isEmpty(proxy.secret)) {
                    // MTProto proxy
                    proxyInfo = new SharedConfig.ProxyInfo(proxy.host, proxy.port, "", "", proxy.secret);
                } else {
                    // SOCKS5 proxy
                    proxyInfo = new SharedConfig.ProxyInfo(proxy.host, proxy.port, "", "", "");
                }

                // Add to proxy list if not already there
                boolean exists = false;
                for (SharedConfig.ProxyInfo existing : SharedConfig.proxyList) {
                    if (existing.address.equals(proxy.host) && existing.port == proxy.port) {
                        exists = true;
                        proxyInfo = existing;
                        break;
                    }
                }

                if (!exists) {
                    SharedConfig.addProxy(proxyInfo);
                }

                // Set as current proxy
                SharedConfig.setCurrentProxy(proxyInfo);

                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + ": Proxy applied: " + proxy.host + ":" + proxy.port);
                }
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(TAG + ": Failed to apply proxy", e);
                }
            }
        });
    }

    /**
     * Get cached proxies.
     */
    public static List<ProxyInfo> getCachedProxies() {
        return new ArrayList<>(cachedProxies);
    }

    /**
     * Save a proxy to persistent storage.
     */
    public static void saveProxy(ProxyInfo proxy) {
        if (ApplicationLoader.applicationContext == null) return;
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString("saved_proxies", "");

        String proxyStr = proxy.host + ":" + proxy.port;
        if (!TextUtils.isEmpty(proxy.secret)) {
            proxyStr += ":" + proxy.secret;
        }

        if (!saved.contains(proxyStr)) {
            if (!TextUtils.isEmpty(saved)) {
                saved += "\n";
            }
            saved += proxyStr;
            prefs.edit().putString("saved_proxies", saved).apply();
        }
    }

    /**
     * Load saved proxies from persistent storage.
     */
    private static List<ProxyInfo> loadSavedProxies() {
        List<ProxyInfo> proxies = new ArrayList<>();
        if (ApplicationLoader.applicationContext == null) return proxies;

        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString("saved_proxies", "");

        if (!TextUtils.isEmpty(saved)) {
            String[] lines = saved.split("\n");
            for (String line : lines) {
                ProxyInfo proxy = parseProxyLine(line.trim());
                if (proxy != null) {
                    proxies.add(proxy);
                }
            }
        }

        return proxies;
    }

    /**
     * Check if proxies need to be refreshed.
     */
    public static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastFetchTime > REFRESH_INTERVAL_MS) {
            fetchAndApplyBestProxy();
        }
    }

    /**
     * Proxy info holder.
     */
    public static class ProxyInfo {
        public String host;
        public int port;
        public String secret;
        public long latency;
        public boolean available;

        public ProxyInfo(String host, int port, String secret) {
            this.host = host;
            this.port = port;
            this.secret = secret;
            this.latency = -1;
            this.available = false;
        }

        @Override
        public String toString() {
            return host + ":" + port + (TextUtils.isEmpty(secret) ? " (SOCKS5)" : " (MTProto)");
        }
    }
}
