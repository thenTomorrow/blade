package it.blade.gateway;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/** Configura la verifica dei JWT e le rotte verso autenticazione e servizi applicativi. */
@SpringBootApplication
@LoadBalancerClients({
    @LoadBalancerClient(name = "blade-service-a", configuration = SwarmDiscovery.class),
    @LoadBalancerClient(name = "blade-service-b", configuration = SwarmDiscovery.class)
})
public class GatewayApplication {
    private static final Pattern USER_ID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /** Usa solo la chiave pubblica per verificare la firma RS256, l'issuer e i claim richiesti dal gateway. */
    @Bean
    ReactiveJwtDecoder accessDecoder(@Value("${blade.jwt.public-key}") Resource key,
                                     @Value("${blade.jwt.issuer}") String issuer) throws IOException {
        try (var stream = key.getInputStream()) {
            var decoder = NimbusReactiveJwtDecoder.withPublicKey(RsaKeyConverters.x509().convert(stream))
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), GatewayApplication::validateAccess));
            return decoder;
        }
    }

    /**
     * Rifiuta token non destinati all'API o scaduti e verifica che ID e ruoli
     * rispettino il formato previsto dagli header inoltrati ai servizi.
     */
    private static OAuth2TokenValidatorResult validateAccess(Jwt jwt) {
        if (List.of("blade-api").equals(jwt.getAudience()) && "access".equals(jwt.getClaims().get("token_use"))
                && jwt.getClaims().get("sub") instanceof String subject && USER_ID.matcher(subject).matches()
                && jwt.getExpiresAt() != null && Instant.now().isBefore(jwt.getExpiresAt())
                && jwt.getClaims().get("roles") instanceof List<?> roles
                && !roles.isEmpty() && roles.size() <= 32
                && roles.stream().allMatch(role -> role instanceof String text && ROLE.matcher(text).matches())) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token claims", null));
    }

    /** Lascia pubbliche le POST di login e refresh, protegge le API con Bearer JWT e nega le altre rotte. */
    @Bean
    SecurityWebFilterChain apiSecurity(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.POST, "/auth/login", "/auth/refresh").permitAll()
                        .pathMatchers("/api/a/**", "/api/b/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }

    /** Instrada le richieste verso auth, A e B dopo aver applicato i filtri di sicurezza e identita. */
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder,
                        @Value("${blade.routes.auth}") String authUri,
                        @Value("${blade.routes.service-a}") String serviceAUri,
                        @Value("${blade.routes.service-b}") String serviceBUri) {
        return builder.routes()
                .route("auth", r -> r.path("/auth/login", "/auth/refresh").and().method(HttpMethod.POST)
                        .filters(f -> f.removeRequestHeader("X-User-Id")
                                .removeRequestHeader("X-User-Roles").removeRequestHeader("Authorization"))
                        .uri(authUri))
                .route("service-a", r -> r.path("/api/a/**").filters(f -> f.filter(new UserHeadersFilter()))
                        .uri(serviceAUri))
                .route("service-b", r -> r.path("/api/b/**").filters(f -> f.filter(new UserHeadersFilter()))
                        .uri(serviceBUri))
                .build();
    }
}
