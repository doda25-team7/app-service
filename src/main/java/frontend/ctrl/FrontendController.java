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
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import java.util.concurrent.atomic.AtomicLong;

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private String modelHost;
    private final RestTemplateBuilder rest;
    private final MeterRegistry registry;

    private final Counter indexRequests;
    private final Counter predictRequests;
    private final Timer predictionLatency;

    // We keep track of counts for the ratio gauge
    private final AtomicLong indexCount = new AtomicLong(0);
    private final AtomicLong predictCount = new AtomicLong(0);

    public FrontendController(RestTemplateBuilder rest, Environment env, MeterRegistry registry) {
        this.rest = rest;
        this.modelHost = env.getProperty("MODEL_HOST");
        this.registry = registry;
        assertModelHost();

        // Initialize Metrics
        this.indexRequests = Counter.builder("frontend_requests_total")
                .tag("endpoint", "index")
                .description("Number of frontend requests by endpoint")
                .register(registry);

        this.predictRequests = Counter.builder("frontend_requests_total")
                .tag("endpoint", "predict")
                .description("Number of frontend requests by endpoint")
                .register(registry);

        this.predictionLatency = Timer.builder("prediction_latency")
                .description("Prediction latency")
                // Histogram bucket configuration if needed, though default Timer is usually
                // sufficient for basic prompt
                .publishPercentileHistogram()
                .register(registry);

        // Gauge for ratio
        Gauge.builder("prediction_ratio", this, FrontendController::calculateRatio)
                .description("Ratio of predictions to index visits")
                .register(registry);
    }

    private double calculateRatio() {
        long idx = indexCount.get();
        return idx == 0 ? 0.0 : (double) predictCount.get() / idx;
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

    @GetMapping("")
    public String redirectToSlash(HttpServletRequest request) {
        // relative REST requests in JS will end up on / and not on /sms
        return "redirect:" + request.getRequestURI() + "/";
    }

    @GetMapping("/")
    public String index(Model m) {
        indexRequests.increment();
        indexCount.incrementAndGet();
        m.addAttribute("hostname", modelHost);
        return "sms/index";
    }

    @PostMapping({ "", "/" })
    @ResponseBody
    public Sms predict(@RequestBody Sms sms) {
        predictRequests.increment();
        predictCount.incrementAndGet();
        System.out.printf("Requesting prediction for \"%s\" ...\n", sms.sms);

        Sms result = predictionLatency.record(() -> {
            sms.result = getPrediction(sms);
            return sms;
        });

        System.out.printf("Prediction: %s\n", result.result);
        return result;
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