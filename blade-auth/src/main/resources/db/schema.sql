-- MySQL 8.0.17+. Eseguire come amministratore, non con l'account dell'applicazione.
CREATE DATABASE IF NOT EXISTS blade CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
USE blade;

CREATE TABLE IF NOT EXISTS `user` (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    username VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL UNIQUE,
    password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CHECK (REGEXP_LIKE(id, '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$', 'c')),
    CHECK (password_hash LIKE '$argon2id$%')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `role` (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    CHECK (REGEXP_LIKE(code, '^[A-Z][A-Z0-9_]{0,31}$', 'c'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_role (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES `role` (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_access (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    KEY user_access_expires_idx (expires_at),
    CHECK (expires_at > issued_at)
) ENGINE=InnoDB;

INSERT INTO `role` (code) VALUES ('USER'), ('READER') ON DUPLICATE KEY UPDATE code = `role`.code;
