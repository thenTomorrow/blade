# Blade

Scocca backend con Java 21, Spring Boot **4.1.2**, Spring Cloud **2025.1.4** e Gradle Wrapper **9.4.1**. Le quattro applicazioni Spring si compilano insieme dalla radice oppure singolarmente; usano tutte `application.properties`.

All'avvio ogni applicazione Spring Boot mostra in console un banner ASCII con il proprio nome, definito in `src/main/resources/banner.txt` nel relativo modulo.

Le quattro applicazioni usano Log4j2, configurato in `src/main/resources/log4j2.xml` in ciascun modulo. I log escono su `STDOUT` tramite un appender asincrono, con timestamp `yyyy-MM-dd HH:mm:ss` e colori per livello.

```text
Client HTTPS -> Nginx -> blade-api-gateway -> blade-auth      (login / refresh)
                                           -> blade-service-a
                                           -> blade-service-b
```

Solo Nginx pubblica una porta. Distribuisce le richieste alle repliche del gateway usando il DNS di Swarm. Spring Cloud Gateway usa Spring Cloud LoadBalancer in round-robin per A e B, risolvendo gli IP dei singoli task (`tasks.<servizio>`). L'auth è raggiunta tramite il VIP privato di Swarm.

Il gateway verifica firma RS256, issuer, audience, scadenza, tipo del token e claim d'identità. Rimuove il bearer prima di inoltrare la richiesta e sostituisce `X-User-Id` e `X-User-Roles` con ID e ruoli ricavati dal token; i ruoli viaggiano separati da virgola. A e B non decodificano JWT. **Non pubblicare le loro porte e non collegare container non fidati alle loro overlay**: la fiducia negli header dipende dall'isolamento di rete.

## Avvio locale con Docker Swarm

Prerequisiti: Docker Engine (oppure Docker Desktop con container Linux), OpenSSL e JDK 21 per i build/test fuori da Docker. Esegui i comandi da `blade`. Su Windows puoi usare Git Bash per i blocchi shell.

1. Inizializza Swarm, se non è già attivo, e costruisci le immagini locali:

   ```sh
   docker swarm init
   for m in nginx blade-api-gateway blade-auth blade-service-a blade-service-b; do
     docker build -t "blade/$m:local" "$m" || break
   done
   ```

2. Prepara un certificato locale e la coppia RSA per i JWT. Non riutilizzare queste chiavi in produzione.

   ```sh
   mkdir -p .secrets
   openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
     -keyout .secrets/tls.key -out .secrets/tls.pem \
     -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .secrets/jwt.key
   openssl pkey -in .secrets/jwt.key -pubout -out .secrets/jwt.pem
   docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 blade
   ```

   Nell'ultimo comando scegli una password. Copia **solo** l'hash BCrypt (la parte dopo `blade:`) in `.secrets/password.bcrypt`, come testo UTF-8 senza BOM. Non inserire la password in chiaro nel file. Se Git Bash converte erroneamente `/CN=localhost` in un percorso Windows, anteponi `MSYS_NO_PATHCONV=1` al comando `openssl req`.

3. Registra i secret sul manager e avvia lo stack locale:

   ```sh
   docker secret create blade_tls_cert .secrets/tls.pem
   docker secret create blade_tls_key .secrets/tls.key
   docker secret create blade_jwt_private_key .secrets/jwt.key
   docker secret create blade_jwt_public_key .secrets/jwt.pem
   docker secret create blade_password_hash .secrets/password.bcrypt
   docker stack deploy --resolve-image never -c docker-stack.yml -c docker-compose.override.yml blade
   docker stack services blade
   ```

   L'override locale si usa con `docker stack deploy`, non con `docker compose up`: anche sul singolo nodo usi il DNS e il bilanciamento di Swarm. Per fermare lo stack, usa `docker stack rm blade`; i secret esterni restano disponibili.

## Prova con Postman

Usa `https://localhost:8443`. Aggiungi il certificato locale di test tra quelli attendibili in Postman o nel sistema. Non disabilitare la verifica TLS per la produzione.

| Metodo e URL | Dati | Risultato atteso |
|---|---|---|
| `POST /auth/login` | JSON: `{"username":"blade","password":"<password scelta>"}` | `200`, `access_token` (300 s) e `refresh_token` (86.400 s) |
| `GET /api/a/whoami` | `Authorization: Bearer <access_token>` | `200`, servizio `blade-service-a`, `userId`, `roles` |
| `GET /api/b/whoami` | `Authorization: Bearer <access_token>` | `200`, servizio `blade-service-b`, `userId`, `roles` |
| `POST /auth/refresh` | JSON: `{"refresh_token":"<refresh_token>"}` | `200`, nuovo `access_token`; il `refresh_token` conserva la scadenza originale |

Invia le POST con `Content-Type: application/json` e senza header `Authorization`. Credenziali errate, JWT scaduti e uso del tipo di token sbagliato restituiscono `401`. Per vedere che gli header del client non vengono accettati, aggiungi `X-User-Roles: ADMIN` a una GET autenticata: il servizio deve restituire i ruoli del token, non `ADMIN`.

L'auth dimostrativa supporta un solo utente configurabile con `BLADE_AUTH_USERNAME`, `BLADE_AUTH_USER_ID` (UUID) e `BLADE_AUTH_ROLES` (es. `USER,READER`). Non c'è un database utenti. Il refresh è stateless e riutilizzabile fino a scadenza: non sono implementati logout, rotazione o revoca individuale. Prima di aprire il servizio a utenti reali, adotta una gestione utenti e sessioni adatta alla produzione.

## Deploy multi-host

- Usa nodi Linux su IP raggiungibili della stessa rete. Sul manager inizializza Swarm con `docker swarm init --advertise-addr <IP_LAN_MANAGER>`; sugli altri nodi esegui il comando restituito da `docker swarm join-token worker`.
- Tra i nodi consenti `2377/tcp` verso i manager, `7946/tcp+udp`, `4789/udp` e protocollo IP 50 (ESP) per le overlay cifrate. Non esporre questi canali alla rete pubblica. Il traffico dei client entra solo da `443/tcp`. Il routing mesh accetta HTTPS sugli IP dei nodi e lo consegna a Nginx.
- Pubblica le cinque immagini in un registry raggiungibile da tutti i nodi. Usa un tag di release immutabile e imposta `BLADE_IMAGE_PREFIX` (es. `registry.example.com/team/blade`) e `BLADE_IMAGE_TAG` sul manager prima del deploy. Per ogni componente costruisci e pubblica `$BLADE_IMAGE_PREFIX/<componente>:$BLADE_IMAGE_TAG`.
- Registra i secret nel cluster di produzione: certificato TLS con catena completa, chiave corrispondente, nuova coppia RSA JWT, hash password. Usa nomi distinti se condividi lo stesso cluster con altri ambienti. I nomi esterni possono essere sovrascritti con `BLADE_TLS_CERT_SECRET`, `BLADE_TLS_KEY_SECRET`, `BLADE_JWT_PRIVATE_SECRET`, `BLADE_JWT_PUBLIC_SECRET` e `BLADE_PASSWORD_SECRET`. Usare per produzione un nome stack distinto da quello locale: per cambiare le opzioni di cifratura di una overlay esistente occorre ricrearla.
- Punta il DNS del backend agli ingressi Swarm o a un bilanciatore TCP sulla porta 443. Il nome usato dai client deve essere nel certificato di Nginx.

```sh
docker stack deploy --with-registry-auth -c docker-stack.yml -c docker-stack.prod.yml blade-prod
docker stack services blade-prod
docker service scale blade-prod_blade-api-gateway=2 blade-prod_blade-service-a=2
```

Lo scale è facoltativo: di default c'è una replica per servizio. In produzione, due repliche del gateway richiedono due nodi disponibili (al massimo una replica per nodo). Nginx parte con una sola replica, quindi non è ad alta disponibilità di default.

### Build e test

```powershell
.\gradlew.bat build
docker stack config -c docker-stack.yml -c docker-compose.override.yml
docker stack config -c docker-stack.yml -c docker-stack.prod.yml
```

Su Linux/macOS usa `sh ./gradlew build`.
