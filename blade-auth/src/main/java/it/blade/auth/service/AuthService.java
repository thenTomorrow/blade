package it.blade.auth.service;

import it.blade.auth.dto.AuthTokens;
import it.blade.auth.repository.AuthAccounts;
import it.blade.auth.security.Passwords;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verifica le credenziali su MySQL e associa ogni refresh token a una sessione revocabile. */
@Service
public class AuthService {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_TTL = Duration.ofDays(1);
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern USER_ID = Pattern.compile("[1-9]\\d{0,18}");
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private final PasswordEncoder passwords = Passwords.encoder();
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
    private final AuthAccounts accounts;
    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final String issuer;

    AuthService(AuthAccounts accounts, JwtEncoder encoder, JwtDecoder refreshDecoder,
                @Value("${blade.jwt.issuer}") String issuer) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.refreshDecoder = refreshDecoder;
        this.issuer = issuer;
    }

    /** Verifica le credenziali con Argon2id e apre una sessione di 24 ore. */
    public AuthTokens login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 64 || !Passwords.valid(password)) {
            throw unauthorized();
        }
        var user = accounts.findByUsername(username).filter(account -> username.equals(account.username())).orElse(null);
        boolean accepted = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
        if (!accepted || user == null || !user.enabled()) {
            throw unauthorized();
        }
        var roles = roles(user.id());
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String sessionId = UUID.randomUUID().toString();
        String subject = user.id().toString();
        String refresh = issue(now, REFRESH_TTL, "blade-auth:refresh", "refresh", subject, sessionId, List.of());
        var response = response(now, refresh, REFRESH_TTL.toSeconds(), subject, roles);
        accounts.createAccess(new AuthAccounts.UserAccess(sessionId, user.id(), now, now.plus(REFRESH_TTL), null, null));
        return response;
    }

    /**
     * Accetta solo un refresh token valido con una sessione attiva.
     * Emette un nuovo access token senza ruotare il refresh token o prolungarne la scadenza.
     */
    @Transactional
    public AuthTokens refresh(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            throw unauthorized();
        }
        try {
            var jwt = refreshDecoder.decode(value);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Long userId = userId(jwt.getSubject());
            if (!List.of("blade-auth:refresh").equals(jwt.getAudience())
                    || !"refresh".equals(jwt.getClaimAsString("token_use"))
                    || userId == null || !validSession(jwt.getId()) || jwt.getExpiresAt() == null
                    || !now.isBefore(jwt.getExpiresAt())
                    || !accounts.useAccess(jwt.getId(), userId, now, jwt.getExpiresAt())) {
                throw unauthorized();
            }
            return response(now.truncatedTo(ChronoUnit.SECONDS), value,
                    Duration.between(now, jwt.getExpiresAt()).toSeconds(), userId.toString(), roles(userId));
        } catch (JwtException exception) {
            throw unauthorized();
        }
    }

    private static Long userId(String value) {
        if (value == null || !USER_ID.matcher(value).matches()) {
            return null;
        }
        return Long.valueOf(value);
    }

    private static boolean validSession(String value) {
        return value != null && SESSION_ID.matcher(value).matches();
    }

    private List<String> roles(Long userId) {
        var roles = accounts.roles(userId);
        if (roles.isEmpty() || roles.size() > 32 || !roles.stream().allMatch(role -> ROLE.matcher(role).matches())) {
            throw unauthorized();
        }
        return roles;
    }

    /** Emette l'access token e restituisce entrambi i token. */
    private AuthTokens response(Instant now, String refresh, long refreshExpiresIn, String userId, List<String> roles) {
        return new AuthTokens(
                issue(now, ACCESS_TTL, "blade-api", "access", userId, UUID.randomUUID().toString(), roles), refresh, "Bearer",
                ACCESS_TTL.toSeconds(), refreshExpiresIn);
    }

    /** Firma un JWT per l'uso e l'audience indicati; include i ruoli solo negli access token. */
    private String issue(Instant now, Duration lifetime, String audience, String use,
                         String userId, String id, List<String> roles) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId).audience(List.of(audience))
                .issuedAt(now).expiresAt(now.plus(lifetime)).id(id)
                .claim("token_use", use);
        if ("access".equals(use)) {
            claims.claim("roles", roles);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build())).getTokenValue();
    }

    private AuthenticationRejected unauthorized() {
        return new AuthenticationRejected();
    }

    /** Non espone al client quale controllo sia fallito. */
    public static final class AuthenticationRejected extends RuntimeException {
        private static final long serialVersionUID = 4539531141930064470L;

		private AuthenticationRejected() {}
    }
}
