package it.blade.auth.dto;

/** Coppia di token restituita al client, con durata residua espressa in secondi. */
public record AuthTokens(String access_token, String refresh_token, String token_type, long expires_in,
                         long refresh_expires_in) {}
