package it.blade.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ResponseStatusException;

/** Verifica emissione dei JWT, rinnovo dell'access token e rifiuto delle credenziali errate. */
class AuthControllerTest {
    private static AuthController auth;
    private static JwtDecoder decoder;

    /** Prepara chiavi effimere e un hash BCrypt di test senza usare i secret del deploy. */
    @BeforeAll
    static void setUp() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var publicKey = (RSAPublicKey) keys.getPublic();
        var encoder = NimbusJwtEncoder.withKeyPair(publicKey, (RSAPrivateKey) keys.getPrivate())
                .algorithm(SignatureAlgorithm.RS256).build();
        decoder = new AuthApplication().refreshDecoder(publicKey, "blade-test");
        var hash = new ByteArrayResource(new BCryptPasswordEncoder().encode("test-password").getBytes());
        auth = new AuthController(encoder, decoder, "blade-test", "blade", UUID.randomUUID(), "USER,READER", hash);
    }

    /** Controlla durata e audience dei token e che il refresh non estenda la sessione originale. */
    @Test
    void issuesDifferentTokensAndRefreshesWithoutExtendingTheSession() {
        var login = auth.login(new AuthController.Login("blade", "test-password"));
        assertThat(login.getHeaders().getCacheControl()).isEqualTo("no-store");
        var tokens = login.getBody();
        var access = decoder.decode(tokens.access_token());
        var refresh = decoder.decode(tokens.refresh_token());
        assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(Duration.between(refresh.getIssuedAt(), refresh.getExpiresAt())).isEqualTo(Duration.ofDays(1));
        assertThat(access.getClaimAsStringList("roles")).containsExactly("USER", "READER");
        assertThat(refresh.hasClaim("roles")).isFalse();
        assertThat(access.getAudience()).containsExactly("blade-api");
        assertThat(refresh.getAudience()).containsExactly("blade-auth:refresh");

        var renewed = auth.refresh(new AuthController.Refresh(tokens.refresh_token())).getBody();
        assertThat(renewed.refresh_token()).isEqualTo(tokens.refresh_token());
        assertThat(renewed.access_token()).isNotEqualTo(tokens.access_token());
        assertThatThrownBy(() -> auth.refresh(new AuthController.Refresh(tokens.access_token())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }

    /** Verifica che una password errata produca un 401 anche con il nome utente corretto. */
    @Test
    void rejectsWrongCredentials() {
        assertThatThrownBy(() -> auth.login(new AuthController.Login("blade", "wrong")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }
}
