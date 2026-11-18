package it.blade.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Gestisce login e refresh stateless per il singolo utente configurato nel servizio. */
@RestController
@RequestMapping("/auth")
class AuthController {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_TTL = Duration.ofDays(1);
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$(?:1[0-4])\\$[./A-Za-z0-9]{53}");
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final String issuer;
    private final String username;
    private final String userId;
    private final List<String> roles;
    private final String passwordHash;

    /** Carica l'hash BCrypt, normalizza i ruoli e rifiuta configurazioni non valide all'avvio. */
    AuthController(JwtEncoder encoder, JwtDecoder refreshDecoder,
                   @Value("${blade.jwt.issuer}") String issuer,
                   @Value("${blade.auth.username}") String username,
                   @Value("${blade.auth.user-id}") UUID userId,
                   @Value("${blade.auth.roles}") String roles,
                   @Value("${blade.auth.password-hash}") Resource passwordHash) throws java.io.IOException {
        this.encoder = encoder;
        this.refreshDecoder = refreshDecoder;
        this.issuer = issuer;
        this.username = username;
        this.userId = userId.toString();
        this.roles = Arrays.stream(roles.split(",", -1)).map(String::strip).distinct().toList();
        this.passwordHash = passwordHash.getContentAsString(UTF_8).strip();
        if (username.isBlank() || this.roles.size() > 32
                || !this.roles.stream().allMatch(role -> ROLE.matcher(role).matches())
                || !BCRYPT.matcher(this.passwordHash).matches()) {
            throw new IllegalStateException("Configure a username, valid roles and a BCrypt hash (cost 10-14)");
        }
    }

    /** Verifica le credenziali con BCrypt ed emette un access token da 5 minuti e un refresh token da 24 ore. */
    @PostMapping("/login")
    ResponseEntity<Tokens> login(@RequestBody Login request) {
        String password = request.password();
        if (password == null || password.isEmpty() || password.length() > 72
                || password.getBytes(UTF_8).length > 72 || !passwords.matches(password, passwordHash)
                || !username.equals(request.username())) {
            throw unauthorized();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return response(now, issue(now, REFRESH_TTL, "blade-auth:refresh", "refresh"), REFRESH_TTL.toSeconds());
    }

    /**
     * Accetta solo un refresh token valido per l'utente configurato.
     * Emette un nuovo access token senza ruotare il refresh token o prolungarne la scadenza.
     */
    @PostMapping("/refresh")
    ResponseEntity<Tokens> refresh(@RequestBody Refresh request) {
        String value = request.refresh_token();
        if (value == null || value.isBlank()) {
            throw unauthorized();
        }
        try {
            var jwt = refreshDecoder.decode(value);
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            if (!List.of("blade-auth:refresh").equals(jwt.getAudience())
                    || !"refresh".equals(jwt.getClaimAsString("token_use"))
                    || !userId.equals(jwt.getSubject()) || jwt.getExpiresAt() == null
                    || !now.isBefore(jwt.getExpiresAt())) {
                throw unauthorized();
            }
            return response(now, value, Duration.between(now, jwt.getExpiresAt()).toSeconds());
        } catch (JwtException exception) {
            throw unauthorized();
        }
    }

    /** Emette l'access token e restituisce entrambi i token con {@code Cache-Control: no-store}. */
    private ResponseEntity<Tokens> response(Instant now, String refresh, long refreshExpiresIn) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Tokens(
                issue(now, ACCESS_TTL, "blade-api", "access"), refresh, "Bearer",
                ACCESS_TTL.toSeconds(), refreshExpiresIn));
    }

    /** Firma un JWT per l'uso e l'audience indicati; include i ruoli solo negli access token. */
    private String issue(Instant now, Duration lifetime, String audience, String use) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId).audience(List.of(audience))
                .issuedAt(now).expiresAt(now.plus(lifetime)).id(UUID.randomUUID().toString())
                .claim("token_use", use);
        if ("access".equals(use)) {
            claims.claim("roles", roles);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build())).getTokenValue();
    }

    /** Restituisce un errore 401 generico senza rivelare quale controllo sia fallito. */
    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    /** Corpo JSON della richiesta di login con le credenziali dell'utente. */
    record Login(String username, String password) {}
    /** Corpo JSON della richiesta di refresh; non accetta un access token al suo posto. */
    record Refresh(String refresh_token) {}
    /** Coppia di token restituita al client, con durata residua espressa in secondi. */
    record Tokens(String access_token, String refresh_token, String token_type, long expires_in,
                  long refresh_expires_in) {}
}
