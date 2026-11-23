-- MySQL 8.0.17+. Eseguire una sola volta sullo schema blade con user_access.id CHAR(36).
-- Eseguire il backup e fermare blade-auth prima dell'ALTER; poi avviare la nuova versione.
USE blade;

ALTER TABLE user_access
    DROP PRIMARY KEY,
    CHANGE COLUMN id session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY user_access_session_id_uk (session_id);