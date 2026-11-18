package it.blade.gateway;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Registra le richieste processate dal gateway senza esporre token, body o query string. */
final class GatewayRequestLoggingFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var method = request.getMethod();
        var path = request.getURI().getRawPath();
        var started = System.nanoTime();

        return chain.filter(exchange)
                .doOnError(error -> log.error("Gateway request failed: method={}, path={}", method, path, error))
                .doFinally(signal -> {
                    var status = exchange.getResponse().getStatusCode();
                    var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    log.info("Gateway request processed: method={}, path={}, status={}, durationMs={}",
                            method, path, status == null ? "unset" : status.value(), elapsed);
                });
    }
}
