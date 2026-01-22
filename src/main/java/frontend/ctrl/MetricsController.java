package frontend.ctrl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MetricsController {

    public static final AtomicLong indexRequests = new AtomicLong(0);
    public static final AtomicLong predictRequests = new AtomicLong(0);

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> activeSessionsByPage =
            new ConcurrentHashMap<>();

    // A session is considered active if it pinged within this window
    private static final long ACTIVE_TTL_MILLIS = 60_000;

    // Stable page labels we always want exported (even if 0)
    public static final String SMS_PAGE_LABEL = "/sms/";
    private static final String[] KNOWN_PAGES = new String[] { SMS_PAGE_LABEL };

    public static void heartbeatEnter(String page, String sessionId) {
        heartbeatPing(page, sessionId);
    }

    public static void heartbeatPing(String page, String sessionId) {
        if (page == null || page.isBlank()) page = SMS_PAGE_LABEL; // safe fallback
        long now = System.currentTimeMillis();
        activeSessionsByPage
                .computeIfAbsent(page, k -> new ConcurrentHashMap<>())
                .put(sessionId, now);
    }

    public static void heartbeatLeave(String page, String sessionId) {
        if (page == null || page.isBlank()) page = SMS_PAGE_LABEL; // safe fallback
        ConcurrentHashMap<String, Long> bySession = activeSessionsByPage.get(page);
        if (bySession != null) {
            bySession.remove(sessionId);
            if (bySession.isEmpty()) {
                activeSessionsByPage.remove(page, bySession);
            }
        }
    }

    private static long countAndCleanupActive(String page) {
        long now = System.currentTimeMillis();
        long cutoff = now - ACTIVE_TTL_MILLIS;

        ConcurrentHashMap<String, Long> bySession = activeSessionsByPage.get(page);
        if (bySession == null) return 0;

        bySession.entrySet().removeIf(e -> e.getValue() < cutoff);

        long count = bySession.size();
        if (count == 0) {
            activeSessionsByPage.remove(page, bySession);
        }
        return count;
    }

    private static final AtomicLong latencyCount = new AtomicLong(0);
    private static double latencySum = 0.0;

    public static synchronized void recordPredictionLatency(double seconds) {
        latencyCount.incrementAndGet();
        latencySum += seconds;
    }

    private static final double[] UI_BUCKETS = {0.1, 0.3, 0.5, 1.0, 2.0, 5.0};

    private static final ConcurrentHashMap<String, AtomicLong> uiHistogram = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> uiCount = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DoubleAdder> uiSum = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> uiRequestsTotal = new ConcurrentHashMap<>();

    public static void observeUiRequest(String endpoint, String method, String status, double seconds) {
        String counterKey = endpoint + "|" + method + "|" + status;
        uiRequestsTotal.computeIfAbsent(counterKey, k -> new AtomicLong(0)).incrementAndGet();

        for (double bucket : UI_BUCKETS) {
            if (seconds <= bucket) {
                String key = endpoint + "|" + method + "|" + status + "|" + bucket;
                uiHistogram.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
            }
        }
        String infKey = endpoint + "|" + method + "|" + status + "|+Inf";
        uiHistogram.computeIfAbsent(infKey, k -> new AtomicLong(0)).incrementAndGet();

        String baseKey = endpoint + "|" + method + "|" + status;
        uiCount.computeIfAbsent(baseKey, k -> new LongAdder()).increment();
        uiSum.computeIfAbsent(baseKey, k -> new DoubleAdder()).add(seconds);
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @ResponseBody
    public String metrics() {
        StringBuilder m = new StringBuilder();

        m.append("# TYPE index_requests_total counter\n");
        m.append("index_requests_total ").append(indexRequests.get()).append("\n");

        m.append("# TYPE predict_requests_total counter\n");
        m.append("predict_requests_total ").append(predictRequests.get()).append("\n");

        // Always export known pages (even if 0)
        m.append("# TYPE active_users gauge\n");
        for (String page : KNOWN_PAGES) {
            long active = countAndCleanupActive(page);
            m.append("active_users{page=\"").append(page).append("\"} ").append(active).append("\n");
        }

        // Export any other pages that might have been tracked dynamically
        for (String page : activeSessionsByPage.keySet()) {
            boolean isKnown = false;
            for (String kp : KNOWN_PAGES) {
                if (kp.equals(page)) { isKnown = true; break; }
            }
            if (isKnown) continue;

            long active = countAndCleanupActive(page);
            m.append("active_users{page=\"").append(page).append("\"} ").append(active).append("\n");
        }

        m.append("# TYPE prediction_latency_seconds summary\n");
        m.append("prediction_latency_seconds_count ").append(latencyCount.get()).append("\n");
        m.append("prediction_latency_seconds_sum ").append(latencySum).append("\n");

        m.append("# TYPE ui_requests_total counter\n");
        for (Map.Entry<String, AtomicLong> e : uiRequestsTotal.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            m.append("ui_requests_total")
             .append("{endpoint=\"").append(parts[0])
             .append("\",method=\"").append(parts[1])
             .append("\",status=\"").append(parts[2])
             .append("\"} ")
             .append(e.getValue().get())
             .append("\n");
        }

        m.append("# TYPE ui_request_duration_seconds histogram\n");
        for (Map.Entry<String, AtomicLong> e : uiHistogram.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            m.append("ui_request_duration_seconds_bucket")
                    .append("{endpoint=\"").append(parts[0])
                    .append("\",method=\"").append(parts[1])
                    .append("\",status=\"").append(parts[2])
                    .append("\",le=\"").append(parts[3])
                    .append("\"} ")
                    .append(e.getValue().get())
                    .append("\n");
        }

        for (Map.Entry<String, LongAdder> e : uiCount.entrySet()) {
            String[] parts = e.getKey().split("\\|");

            m.append("ui_request_duration_seconds_count")
             .append("{endpoint=\"").append(parts[0])
             .append("\",method=\"").append(parts[1])
             .append("\",status=\"").append(parts[2])
             .append("\"} ")
             .append(e.getValue().sum())
             .append("\n");

            DoubleAdder sum = uiSum.get(e.getKey());
            m.append("ui_request_duration_seconds_sum")
             .append("{endpoint=\"").append(parts[0])
             .append("\",method=\"").append(parts[1])
             .append("\",status=\"").append(parts[2])
             .append("\"} ")
             .append(sum == null ? 0.0 : sum.sum())
             .append("\n");
        }

        m.append("# EOF\n");
        return m.toString();
    }
}
