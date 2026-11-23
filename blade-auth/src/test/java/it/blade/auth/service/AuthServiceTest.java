package it.blade.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import it.blade.auth.config.JwtConfiguration;
import it.blade.auth.repository.AuthAccounts;
import it.blade.auth.security.Passwords;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.mockito.ArgumentCaptor;

/** Verifica emissione dei JWT, rinnovo dell'access token e rifiuto delle credenziali errate. */
class AuthServiceTest {
    private static final Long USER_ID = 7L;
    private static String hash;
    private static JwtEncoder encoder;
    private static JwtDecoder decoder;
    private AuthService auth;
    private AuthAccounts accounts;

    /** Prepara chiavi effimere e un hash Argon2id senza usare i secret del deploy. */
    @BeforeAll
    static void setUp() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var publicKey = (RSAPublicKey) keys.getPublic();
        encoder = NimbusJwtEncoder.withKeyPair(publicKey, (RSAPrivateKey) keys.getPrivate())
                .algorithm(SignatureAlgorithm.RS256).build();
        decoder = new JwtConfiguration().refreshDecoder(publicKey, "blade-test");
        hash = Passwords.encoder().encode("test-password");
    }

    @BeforeEach
    void setUpAccounts() {
        accounts = mock(AuthAccounts.class);
        when(accounts.findByUsername("blade")).thenReturn(Optional.of(
                new AuthAccounts.User(USER_ID, "blade", hash, true, Instant.now())));
        when(accounts.roles(USER_ID)).thenReturn(List.of("USER", "READER"));
        auth = new AuthService(accounts, encoder, decoder, "blade-test");
    }

    /** Controlla durata e audience dei token e che il refresh non estenda la sessione originale. */
    @Test
    void issuesDifferentTokensAndRefreshesWithoutExtendingTheSession() {
        var tokens = auth.login("blade", "test-password");
        var access = decoder.decode(tokens.access_token());
        var refresh = decoder.decode(tokens.refresh_token());
        assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(Duration.between(refresh.getIssuedAt(), refresh.getExpiresAt())).isEqualTo(Duration.ofDays(1));
        assertThat(access.getClaimAsStringList("roles")).containsExactly("USER", "READER");
        assertThat(refresh.hasClaim("roles")).isFalse();
        assertThat(access.getAudience()).containsExactly("blade-api");
        assertThat(refresh.getAudience()).containsExactly("blade-auth:refresh");
        var saved = ArgumentCaptor.forClass(AuthAccounts.UserAccess.class);
        verify(accounts).createAccess(saved.capture());
        assertThat(saved.getValue().sessionId()).isEqualTo(refresh.getId());
        assertThat(saved.getValue().userId()).isEqualTo(Long.valueOf(access.getSubject()));
        assertThat(saved.getValue().expiresAt()).isEqualTo(refresh.getExpiresAt());
        when(accounts.useAccess(eq(refresh.getId()), eq(USER_ID), any(), eq(refresh.getExpiresAt()))).thenReturn(true);
        when(accounts.roles(USER_ID)).thenReturn(List.of("READER"));

        var renewed = auth.refresh(tokens.refresh_token());
        assertThat(renewed.refresh_token()).isEqualTo(tokens.refresh_token());
        assertThat(renewed.access_token()).isNotEqualTo(tokens.access_token());
        assertThat(decoder.decode(renewed.access_token()).getClaimAsStringList("roles")).containsExactly("READER");
        assertThatThrownBy(() -> auth.refresh(tokens.access_token()))
                .isInstanceOf(AuthService.AuthenticationRejected.class);
    }

    /** Verifica che password errata e utente inesistente siano rifiutati allo stesso modo. */
    @Test
    void rejectsWrongCredentials() {
        assertThatThrownBy(() -> auth.login("blade", "wrong"))
                .isInstanceOf(AuthService.AuthenticationRejected.class);
        assertThatThrownBy(() -> auth.login("unknown", "test-password"))
                .isInstanceOf(AuthService.AuthenticationRejected.class);
        verify(accounts).findByUsername("blade");
        verify(accounts).findByUsername("unknown");
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void rejectsRefreshWithoutAnActiveDatabaseSession() {
        var tokens = auth.login("blade", "test-password");
        assertThatThrownBy(() -> auth.refresh(tokens.refresh_token()))
                .isInstanceOf(AuthService.AuthenticationRejected.class);
    }
}