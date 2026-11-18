package it.blade.servicea;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Espone le API del servizio A e si fida degli header inoltrati dal gateway sulla rete interna. */
@SpringBootApplication
@RestController
public class ServiceAApplication {
    private static final Logger log = LoggerFactory.getLogger(ServiceAApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ServiceAApplication.class, args);
    }

    /** Mostra l'ID ricevuto dal gateway e converte i ruoli separati da virgole in una lista. */
    @GetMapping("/api/a/whoami")
    Identity whoami(@RequestHeader("X-User-Id") String userId,
                    @RequestHeader("X-User-Roles") String roles) {
        log.info("Request processed: method=GET, path=/api/a/whoami, userId={}, roleCount={}",
                userId, roles.split(",", -1).length);
        return new Identity("blade-service-a", userId, Arrays.asList(roles.split(",")));
    }

    /** Risposta diagnostica con il nome del servizio, l'ID utente e i ruoli ricevuti. */
    record Identity(String service, String userId, List<String> roles) {}
}
