package it.blade.auth;

import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Avvia il servizio di autenticazione e configura la firma e la verifica dei JWT. */
@SpringBootApplication
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

    /** Legge la chiave pubblica RSA da una risorsa PEM in formato X.509. */
    @Bean
    RSAPublicKey publicKey(@Value("${blade.jwt.public-key}") Resource resource) throws IOException {
        try (var stream = resource.getInputStream()) {
            return RsaKeyConverters.x509().convert(stream);
        }
    }

    /** Configura la firma RS256 e rifiuta coppie RSA non corrispondenti o inferiori a 2048 bit. */
    @Bean
    JwtEncoder jwtEncoder(RSAPublicKey publicKey,
                          @Value("${blade.jwt.private-key}") Resource resource) throws IOException {
        try (var stream = resource.getInputStream()) {
            RSAPrivateKey key = RsaKeyConverters.pkcs8().convert(stream);
            if (!key.getModulus().equals(publicKey.getModulus()) || key.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("JWT keys must be a matching RSA pair of at least 2048 bits");
            }
            return NimbusJwtEncoder.withKeyPair(publicKey, key).algorithm(SignatureAlgorithm.RS256).build();
        }
    }

    /** Verifica firma, issuer e vincoli temporali; il controller controlla i claim specifici del refresh. */
    @Bean
    JwtDecoder refreshDecoder(RSAPublicKey publicKey, @Value("${blade.jwt.issuer}") String issuer) {
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
