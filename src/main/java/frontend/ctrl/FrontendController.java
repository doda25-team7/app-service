package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private static final String SMS_PAGE_LABEL = "/sms/";

    private String modelHost;
    private RestTemplateBuilder rest;

    public FrontendController(RestTemplateBuilder rest, Environment env) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        assertModelHost();
    }

    private void assertModelHost() {
        if (modelHost == null || modelHost.strip().isEmpty()) {
            System.err.println("ERROR: ENV variable MODEL_HOST is null or empty");
            System.exit(1);
        }
        modelHost = modelHost.strip();
        if (modelHost.indexOf("://") == -1) {
            var m = "ERROR: ENV variable MODEL_HOST is missing protocol, like \"http://...\" (was: \"%s\")\n";
            System.err.printf(m, modelHost);
            System.exit(1);
        } else {
            System.out.printf("Working with MODEL_HOST=\"%s\"\n", modelHost);
        }
    }

    /**
     * Normalize endpoint labels so metrics do not split between "/sms" and "/sms/".
     */
    private static String normalizeEndpointForMetrics(String requestUri) {
        if (requestUri == null) return SMS_PAGE_LABEL;
        if (requestUri.equals("/sms")) return SMS_PAGE_LABEL;
        return requestUri;
    }

    @GetMapping("")
    public String redirectToSlash(HttpServletRequest request) {
        // Track redirect endpoint as a UI request too
        final String endpoint = normalizeEndpointForMetrics(request.getRequestURI());
        final String method = request.getMethod();
        final long start = System.nanoTime();
        final String status = "302";

        try {
            return "redirect:" + request.getRequestURI() + "/";
        } finally {
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            MetricsController.observeUiRequest(endpoint, method, status, seconds);
        }
    }

    @GetMapping("/")
    public String index(Model m, HttpServletRequest request) {
        MetricsController.indexRequests.incrementAndGet();

        final String endpoint = normalizeEndpointForMetrics(request.getRequestURI());
        final String method = request.getMethod();
        final long start = System.nanoTime();
        String status = "200";

        try {
            m.addAttribute("hostname", modelHost);
            return "sms/index";
        } catch (RuntimeException ex) {
            status = "500";
            throw ex;
        } finally {
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            MetricsController.observeUiRequest(endpoint, method, status, seconds);
        }
    }

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms, HttpServletRequest request) {
        MetricsController.predictRequests.incrementAndGet();

        final String endpoint = normalizeEndpointForMetrics(request.getRequestURI());
        final String method = request.getMethod();
        final long start = System.nanoTime();
        String status = "200";

        try {
            System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);

            // Prediction latency (your summary metrics)
            long predStart = System.nanoTime();
            sms.result = getPrediction(sms);
            double predSeconds = (System.nanoTime() - predStart) / 1_000_000_000.0;
            MetricsController.recordPredictionLatency(predSeconds);

            System.out.printf("Prediction: %s\n", sms.result);
            return sms;
        } catch (RuntimeException ex) {
            status = "500";
            throw ex;
        } finally {
            // UI request duration histogram (overall handler time)
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            MetricsController.observeUiRequest(endpoint, method, status, seconds);
        }
    }

    // ---------------------------------------------------------------------
    // Heartbeat endpoints for active user tracking
    // ---------------------------------------------------------------------

    /**
     * Called by the browser when the user opens the page.
     * Example: POST /sms/active/enter?page=/sms/
     */
    @PostMapping("/active/enter")
    @ResponseBody
    public void activeEnter(@RequestParam String page, HttpServletRequest request) {
        String sessionId = request.getSession(true).getId();
        MetricsController.heartbeatEnter(page, sessionId);
    }

    /**
     * Called periodically by browser to keep session active.
     * Example: POST /sms/active/ping?page=/sms/
     */
    @PostMapping("/active/ping")
    @ResponseBody
    public void activePing(@RequestParam String page, HttpServletRequest request) {
        String sessionId = request.getSession(true).getId();
        MetricsController.heartbeatPing(page, sessionId);
    }

    /**
     * Best-effort call when tab is closing/unloading.
     * Example: POST /sms/active/leave?page=/sms/
     */
    @PostMapping("/active/leave")
    @ResponseBody
    public void activeLeave(@RequestParam String page, HttpServletRequest request) {
        String sessionId = request.getSession(true).getId();
        MetricsController.heartbeatLeave(page, sessionId);
    }

    private String getPrediction(Sms sms) {
        try {
            var url = new URI(modelHost + "/predict");
            var c = rest.build().postForEntity(url, sms, Sms.class);
            return c.getBody().result.trim();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
