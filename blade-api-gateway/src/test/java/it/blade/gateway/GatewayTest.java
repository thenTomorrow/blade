package it.blade.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifica via HTTP il routing e il confine di fiducia del gateway senza dipendere da Swarm. */
class GatewayTest {
    private static final String USER_ID = "51d4db3c-bb6f-423a-b313-7f6f023a2d15";

    /**
     * Verifica che entrambe le rotte protette rifiutino token mancanti, scaduti o di refresh
     * e che al backend arrivino solo ID e ruoli ricavati dal token valido.
     */
    @Test
    void authenticatesAndRoutesBothServicesWithoutTrustingClientHeaders(@TempDir Path temp) throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var keyFile = Files.writeString(temp.resolve("public.pem"), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        var encoder = NimbusJwtEncoder.withKeyPair((RSAPublicKey) keys.getPublic(),
                (RSAPrivateKey) keys.getPrivate()).build();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                var body = String.join("|", exchange.getRequestURI().getPath(),
                        String.valueOf(headers.get("X-User-Id")), String.valueOf(headers.get("X-User-Roles")),
                        String.valueOf(headers.get("Authorization"))).getBytes(UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        upstream.start();
        try (var app = new SpringApplicationBuilder(GatewayApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0", "--blade.jwt.issuer=blade-test",
                "--blade.jwt.public-key=" + keyFile.toUri(),
                "--blade.discovery.port=" + upstream.getAddress().getPort(),
                "--blade.routes.auth=http://127.0.0.1:" + upstream.getAddress().getPort())) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                String valid = token(encoder, "blade-api", "access", Instant.now().plusSeconds(120));
                String refresh = token(encoder, "blade-auth:refresh", "refresh", Instant.now().plusSeconds(120));
                String expired = token(encoder, "blade-api", "access", Instant.now().minusSeconds(1));
                for (String path : List.of("/api/a/whoami", "/api/b/whoami")) {
                    for (String invalid : List.of("", refresh, expired)) {
                        assertThat(request(client, port, path, invalid).statusCode()).isEqualTo(401);
                    }
                    var response = request(client, port, path, valid);
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).isEqualTo(path + "|[" + USER_ID + "]|[USER,READER]|null");
                }
                var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                        .header("X-User-Id", "spoof").POST(HttpRequest.BodyPublishers.noBody()).build();
                assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).body())
                        .isEqualTo("/auth/login|null|null|null");
            }
        } finally {
            upstream.stop(0);
        }
    }

    /** Firma un JWT di test con audience, uso e scadenza scelti dallo scenario. */
    private static String token(NimbusJwtEncoder encoder, String audience, String use, Instant expiry) {
        return encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer("blade-test")
                .subject(USER_ID).audience(List.of(audience)).claim("token_use", use).claim("roles", List.of("USER", "READER"))
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(expiry).build())).getTokenValue();
    }

    /** Invia una GET con ID e ruoli fittizi, duplicando l'header dell'ID con maiuscole diverse. */
    private static HttpResponse<String> request(HttpClient client, int port, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("x-user-id", "spoof").header("X-User-Id", "second")
                .header("X-User-Roles", "ADMIN").GET();
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
