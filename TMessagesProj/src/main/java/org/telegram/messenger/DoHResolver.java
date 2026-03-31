/*
 * KBGram - DNS-over-HTTPS resolver for bypassing DNS censorship.
 * Resolves Telegram DC addresses via encrypted DoH queries.
 */

package org.telegram.messenger;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DoHResolver {

    private static final String TAG = "KBGram.DoH";

    // DoH providers - multiple for redundancy
    private static final String[] DOH_PROVIDERS = {
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.quad9.net:5053/dns-query",
            "https://doh.opendns.com/dns-query",
            "https://dns.adguard.com/dns-query"
    };

    // Key Telegram domains to resolve
    private static final String[] TELEGRAM_DOMAINS = {
            "telegram.org",
            "core.telegram.org",
            "updates.telegram.org",
            "t.me",
            "telegram.me"
    };

    // DNS cache: domain -> list of IP addresses
    private static final ConcurrentHashMap<String, CachedDnsResult> dnsCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes

    private static volatile boolean enabled = true;
    private static volatile int currentProviderIndex = 0;
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);

    private static final Object lock = new Object();

    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) {
            // Pre-resolve all Telegram domains
            resolveAllTelegramDomains();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Resolve a domain name via DoH.
     * Returns list of IP addresses, or empty list on failure.
     */
    public static List<String> resolve(String domain) {
        if (!enabled || TextUtils.isEmpty(domain)) {
            return new ArrayList<>();
        }

        // Check cache first
        CachedDnsResult cached = dnsCache.get(domain);
        if (cached != null && !cached.isExpired()) {
            return cached.addresses;
        }

        // Try each DoH provider
        for (int attempt = 0; attempt < DOH_PROVIDERS.length; attempt++) {
            int providerIdx = (currentProviderIndex + attempt) % DOH_PROVIDERS.length;
            String provider = DOH_PROVIDERS[providerIdx];

            try {
                List<String> result = queryDoH(provider, domain);
                if (result != null && !result.isEmpty()) {
                    // Cache the result
                    dnsCache.put(domain, new CachedDnsResult(result));
                    // Remember the working provider
                    currentProviderIndex = providerIdx;
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d(TAG + ": Resolved " + domain + " via " + provider + " -> " + result);
                    }
                    return result;
                }
            } catch (Exception e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(TAG + ": DoH query failed for " + domain + " via " + provider, e);
                }
            }
        }

        // All providers failed, return cached result even if expired
        if (cached != null) {
            return cached.addresses;
        }

        return new ArrayList<>();
    }

    /**
     * Perform a DNS-over-HTTPS query using JSON API (RFC 8484 JSON format).
     */
    private static List<String> queryDoH(String provider, String domain) throws Exception {
        String urlStr = provider + "?name=" + domain + "&type=A";
        URL url = new URL(urlStr);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/dns-json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            return parseDnsResponse(response.toString());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Parse DNS JSON response and extract A record IP addresses.
     */
    private static List<String> parseDnsResponse(String json) {
        List<String> addresses = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.optInt("Status", -1) != 0) {
                return addresses;
            }

            JSONArray answers = obj.optJSONArray("Answer");
            if (answers == null) {
                return addresses;
            }

            for (int i = 0; i < answers.length(); i++) {
                JSONObject answer = answers.getJSONObject(i);
                int type = answer.optInt("type", 0);
                if (type == 1) { // A record
                    String data = answer.optString("data", "");
                    if (!TextUtils.isEmpty(data) && isValidIPv4(data)) {
                        addresses.add(data);
                    }
                }
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(TAG + ": Failed to parse DNS response", e);
            }
        }
        return addresses;
    }

    /**
     * Pre-resolve all Telegram domains in background.
     */
    public static void resolveAllTelegramDomains() {
        executor.submit(() -> {
            for (String domain : TELEGRAM_DOMAINS) {
                try {
                    resolve(domain);
                } catch (Exception e) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e(TAG + ": Pre-resolve failed for " + domain, e);
                    }
                }
            }
        });
    }

    /**
     * Clear the DNS cache.
     */
    public static void clearCache() {
        dnsCache.clear();
    }

    /**
     * Get current DoH provider name for display.
     */
    public static String getCurrentProviderName() {
        String url = DOH_PROVIDERS[currentProviderIndex];
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Get all available DoH provider names.
     */
    public static String[] getProviderNames() {
        String[] names = new String[DOH_PROVIDERS.length];
        for (int i = 0; i < DOH_PROVIDERS.length; i++) {
            try {
                names[i] = new URL(DOH_PROVIDERS[i]).getHost();
            } catch (Exception e) {
                names[i] = DOH_PROVIDERS[i];
            }
        }
        return names;
    }

    /**
     * Set the preferred DoH provider by index.
     */
    public static void setPreferredProvider(int index) {
        if (index >= 0 && index < DOH_PROVIDERS.length) {
            currentProviderIndex = index;
        }
    }

    private static boolean isValidIPv4(String ip) {
        if (TextUtils.isEmpty(ip)) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Cached DNS result with TTL.
     */
    private static class CachedDnsResult {
        final List<String> addresses;
        final long timestamp;

        CachedDnsResult(List<String> addresses) {
            this.addresses = addresses;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
