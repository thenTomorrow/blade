# Blade

Scocca backend con Java 21, Spring Boot **4.1.2**, Spring Cloud **2025.1.4** e Gradle Wrapper **9.4.1**. Le quattro applicazioni Spring si compilano insieme dalla radice oppure singolarmente; usano tutte `application.properties`.

All'avvio ogni applicazione Spring Boot mostra in console un banner ASCII con il proprio nome, definito in `src/main/resources/banner.txt` nel relativo modulo.

Le quattro applicazioni usano Logback, configurato in `src/main/resources/logback-spring.xml` in ciascun modulo. I log escono su `STDOUT` tramite un appender asincrono, con timestamp `yyyy-MM-dd HH:mm:ss` e colori per livello.

```text
Client HTTPS -> Nginx -> blade-api-gateway -> blade-auth      (login / refresh)
                                           -> blade-service-a
                                           -> blade-service-b
```

Solo Nginx pubblica una porta. Distribuisce le richieste alle repliche del gateway usando il DNS di Swarm. Spring Cloud Gateway usa Spring Cloud LoadBalancer in round-robin per A e B, risolvendo gli IP dei singoli task (`tasks.<servizio>`). L'auth è raggiunta tramite il VIP privato di Swarm.

Il gateway verifica firma RS256, issuer, audience, scadenza, tipo del token e claim d'identità. Rimuove il bearer prima di inoltrare la richiesta e sostituisce `X-User-Id` e `X-User-Roles` con ID e ruoli ricavati dal token; i ruoli viaggiano separati da virgola. A e B non decodificano JWT. **Non pubblicare le loro porte e non collegare container non fidati alle loro overlay**: la fiducia negli header dipende dall'isolamento di rete.

## Database MySQL per il login

Se il database esiste e `user_access.id` contiene ancora UUID (`CHAR(36)`), fai un backup, ferma `blade-auth` ed esegui [`migrate-user-access-id.sql`](blade-auth/src/main/resources/db/migrate-user-access-id.sql) una sola volta con un account amministrativo prima di avviare la nuova versione. La migrazione conserva sessioni e revoche. Non rieseguire `schema.sql` su un database da conservare: elimina e ricrea le tabelle.

1. In MySQL Workbench, esegui [`schema.sql`](blade-auth/src/main/resources/db/schema.sql) con un account amministrativo (MySQL 8.0.17+). Lo script crea lo schema `blade`, quattro tabelle e i ruoli `USER` e `READER`, ma nessun utente applicativo.
2. Crea un account MySQL dedicato `blade_auth`. Dagli `SELECT` sulle quattro tabelle e `INSERT` e `UPDATE (last_used_at)` su `user_access`. Usa un account amministrativo separato per provisioning, revoche e pulizia.
3. Dalla root genera l'hash Argon2id (64 MiB, 3 iterazioni, parallelismo 1). Al prompt digita la password e premi Invio.

   ```powershell
   .\gradlew.bat --no-daemon :blade-auth:passwordHash
   ```

   In alternativa, senza prompt: `-PplainPassword='password-di-almeno-15-caratteri'`. La password resta nella history del terminale: usala solo in locale.

4. Esegui le seguenti istruzioni in Workbench, sostituendo il segnaposto con l'intera stringa `$argon2id$...` stampata dal comando. Non committare l'hash.

   ```sql
   USE blade;
   START TRANSACTION;
   INSERT INTO `user` (username, password_hash) VALUES ('blade', '<ARGON2ID_HASH>');
   SET @id = LAST_INSERT_ID();
   INSERT INTO user_role (user_id, role_id) SELECT @id, id FROM `role` WHERE code = 'USER';
   COMMIT;
   ```

Per l'avvio da Eclipse imposta `BLADE_DATASOURCE_PASSWORD` nella configurazione di lancio di `blade-auth`. Se diversi dai default, imposta anche `BLADE_DATASOURCE_URL` (`jdbc:mysql://127.0.0.1:3306/blade`) e `BLADE_DATASOURCE_USERNAME` (`blade_auth`). Da Docker Desktop il default usa `host.docker.internal`; MySQL deve accettare connessioni dal container. Su Swarm multi-host specifica un endpoint MySQL privato raggiungibile dai nodi e configura TLS verificato (`sslMode=VERIFY_IDENTITY`).

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
   ```

   Se Git Bash converte erroneamente `/CN=localhost` in un percorso Windows, anteponi `MSYS_NO_PATHCONV=1` al comando `openssl req`.
3. Registra i secret sul manager e avvia lo stack locale:

   ```sh
   docker secret create blade_tls_cert .secrets/tls.pem
   docker secret create blade_tls_key .secrets/tls.key
   docker secret create blade_jwt_private_key .secrets/jwt.key
   docker secret create blade_jwt_public_key .secrets/jwt.pem
   read -rs -p 'Password MySQL di blade_auth: ' db_password; printf '\n'
   printf '%s' "$db_password" | docker secret create blade_mysql_password -
   unset db_password
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

Il login legge utente e ruoli da MySQL. Il refresh rimane riutilizzabile fino alla scadenza: `user_access.session_id` conserva il suo identificativo (`jti`), non il token. `user_access.id` va invece usato come chiave primaria numerica autoincrementale. Per revocare una sessione, valorizza `revoked_at` con `UTC_TIMESTAMP(6)`; disabilitare l'utente blocca login e refresh. Gli access token gia emessi restano validi per un massimo di 5 minuti. Prevedi la pulizia delle sessioni scadute e un limite ai tentativi di login prima di esporre il servizio in produzione.

## Deploy multi-host

- Usa nodi Linux su IP raggiungibili della stessa rete. Sul manager inizializza Swarm con `docker swarm init --advertise-addr <IP_LAN_MANAGER>`; sugli altri nodi esegui il comando restituito da `docker swarm join-token worker`.
- Tra i nodi consenti `2377/tcp` verso i manager, `7946/tcp+udp`, `4789/udp` e protocollo IP 50 (ESP) per le overlay cifrate. Non esporre questi canali alla rete pubblica. Il traffico dei client entra solo da `443/tcp`. Il routing mesh accetta HTTPS sugli IP dei nodi e lo consegna a Nginx.
- Pubblica le cinque immagini in un registry raggiungibile da tutti i nodi. Usa un tag di release immutabile e imposta `BLADE_IMAGE_PREFIX` (es. `registry.example.com/team/blade`) e `BLADE_IMAGE_TAG` sul manager prima del deploy. Per ogni componente costruisci e pubblica `$BLADE_IMAGE_PREFIX/<componente>:$BLADE_IMAGE_TAG`.
- Registra i secret nel cluster di produzione: certificato TLS con catena completa, chiave corrispondente, nuova coppia RSA JWT e password dell'account MySQL. Usa nomi distinti se condividi lo stesso cluster con altri ambienti. I nomi esterni possono essere sovrascritti con `BLADE_TLS_CERT_SECRET`, `BLADE_TLS_KEY_SECRET`, `BLADE_JWT_PRIVATE_SECRET`, `BLADE_JWT_PUBLIC_SECRET` e `BLADE_MYSQL_PASSWORD_SECRET`. Usa un nome stack distinto da quello locale: per cambiare la cifratura di una overlay esistente occorre ricrearla.
- Punta il DNS del backend agli ingressi Swarm o a un bilanciatore TCP sulla porta 443. Il nome usato dai client deve essere nel certificato di Nginx.

```sh
docker stack deploy --with-registry-auth -c docker-stack.yml -c docker-stack.prod.yml blade-prod
docker stack services blade-prod
docker service scale blade-prod_blade-api-gateway=2 blade-prod_blade-service-a=2
```

Lo scale è facoltativo: di default c'è una replica per servizio. In produzione, due repliche del gateway richiedono due nodi disponibili (al massimo una replica per nodo). Nginx parte con una sola replica, quindi non è ad alta disponibilità di default.

### Configurazione Spring opzionale dal nodo

Per leggere i file dal nodo Linux che esegue il container, aggiungi
`-c docker-stack.host-config.yml` come ultimo file del deploy:

```sh
docker stack deploy --with-registry-auth -c docker-stack.yml -c docker-stack.prod.yml -c docker-stack.host-config.yml blade-prod
```

Prima del deploy crea `/opt/blade-api-gateway`, `/opt/blade-auth`,
`/opt/blade-service-a` e `/opt/blade-service-b` su ogni nodo che pu� ospitare
i rispettivi servizi. Swarm richiede che esistano le directory dei bind mount,
anche vuote. Se non vuoi predisporle, non includere l'override.

- `application.properties`, se presente, sovrascrive le chiavi corrispondenti
  del JAR; le altre mantengono il valore interno. Le variabili d'ambiente
  continuano ad avere precedenza e `blade-auth` conserva l'import dei secret.
- `logback-spring.xml`, se presente, sostituisce la configurazione Logback
  interna. Se � gi� impostata `LOGGING_CONFIG`, viene rispettata.
- I file sono montati in sola lettura e devono essere leggibili dall'UID
  `10001` nel container. Tieni i segreti in Swarm, non in questi file.
  Dopo una modifica, riavvia le repliche interessate con
  `docker service update --force <stack>_<servizio>`.

### Log e retention

Tutti i container usano il driver Docker `json-file`, con rotazione a 10 MB per file e fino a 30 file per container (circa 300 MB per replica). La rotazione è basata sulla dimensione, non sul tempo: non garantisce di conservare esattamente 30 giorni. Inoltre, i log locali possono sparire quando Swarm rimuove un container o un nodo. Per una retention garantita e consultabile tra nodi serve inoltrare i log a un sistema centralizzato.

Per seguire i log recenti di tutte le repliche:

```sh
docker service logs --since 1h --follow blade-prod_blade-service-a
```

Per estrarre un singolo giorno, individua i nodi che eseguono il servizio con `docker service ps`, poi su ciascun nodo elenca i container (aggiungi `-a` se vuoi includere container terminati non ancora rimossi):

```sh
docker ps -a --filter label=com.docker.swarm.service.name=blade-prod_blade-service-a --format '{{.ID}} {{.Names}}'
```

Sul nodo che ospita ciascun container, usa il suo ID e l'intervallo desiderato. Gli orari qui sotto sono UTC; modifica data e orario finale per il giorno da consultare:

```sh
docker logs --timestamps --since "2026-11-23T00:00:00Z" --until "2026-11-24T00:00:00Z" <container-id>
```

### Build e test

```powershell
.\gradlew.bat build
docker stack config -c docker-stack.yml -c docker-compose.override.yml
docker stack config -c docker-stack.yml -c docker-stack.prod.yml
```

Su Linux/macOS usa `sh ./gradlew build`.