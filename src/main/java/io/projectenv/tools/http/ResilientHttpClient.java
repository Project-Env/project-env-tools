package io.projectenv.tools.http;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.apache.maven.plugin.logging.Log;

/**
 * HTTP client with retry and per-host rate limiting via Resilience4j.
 * Each target host gets its own independent rate limiter.
 */
public class ResilientHttpClient {

    private static final int MAX_RETRIES = 3;
    private static final Duration WAIT_DURATION = Duration.ofSeconds(2);
    private static final Duration RATE_LIMIT_REFRESH_PERIOD = Duration.ofSeconds(1);
    private static final int RATE_LIMIT_PERMISSIONS_PER_PERIOD = 50;

    private final HttpClient httpClient;
    private final Retry retry;
    private final RateLimiterConfig rateLimiterConfig;
    private final ConcurrentMap<String, RateLimiter> hostRateLimiters = new ConcurrentHashMap<>();

    private ResilientHttpClient(HttpClient httpClient, Retry retry, RateLimiterConfig rateLimiterConfig) {
        this.httpClient = httpClient;
        this.retry = retry;
        this.rateLimiterConfig = rateLimiterConfig;
    }

    public static ResilientHttpClient create(Log log) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMinutes(5))
                .build();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(MAX_RETRIES)
                .waitDuration(WAIT_DURATION)
                .retryExceptions(IOException.class, RuntimeException.class)
                .retryOnResult(response -> response instanceof HttpResponse<?> hr && hr.statusCode() >= 500)
                .build();

        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(RATE_LIMIT_REFRESH_PERIOD)
                .limitForPeriod(RATE_LIMIT_PERMISSIONS_PER_PERIOD)
                .timeoutDuration(Duration.ofMinutes(1))
                .build();

        Retry retry = Retry.of("httpRetry", retryConfig);

        retry.getEventPublisher()
                .onRetry(event -> log.debug("Retry attempt " + event.getNumberOfRetryAttempts()
                        + " due to: " + (event.getLastThrowable() != null
                                ? event.getLastThrowable().getMessage()
                                : "server error")));

        return new ResilientHttpClient(httpClient, retry, rateLimiterConfig);
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        String host = request.uri().getHost();
        RateLimiter rateLimiter = hostRateLimiters.computeIfAbsent(host,
                h -> RateLimiter.of("rateLimiter-" + h, rateLimiterConfig));

        Supplier<HttpResponse<T>> supplier = RateLimiter.decorateSupplier(rateLimiter,
                Retry.decorateSupplier(retry, () -> {
                    try {
                        return httpClient.send(request, bodyHandler);
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        throw new RuntimeException(e);
                    }
                }));

        try {
            return supplier.get();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            throw e;
        }
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
