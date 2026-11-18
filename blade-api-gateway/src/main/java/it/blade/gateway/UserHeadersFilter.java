package it.blade.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Inoltra ai servizi solo ID e ruoli verificati dal gateway, non il JWT del client. */
final class UserHeadersFilter implements GatewayFilter {
    /**
     * Richiede un principal JWT autenticato e sovrascrive i valori di ID e ruoli inviati dal client.
     * I ruoli sono inoltrati come elenco separato da virgole.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .switchIfEmpty(Mono.error(new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(auth -> {
                    var jwt = auth.getToken();
                    var request = exchange.getRequest().mutate().headers(headers -> {
                        headers.remove(HttpHeaders.AUTHORIZATION);
                        headers.set("X-User-Id", jwt.getSubject());
                        headers.set("X-User-Roles", String.join(",", jwt.getClaimAsStringList("roles")));
                    }).build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }
}
