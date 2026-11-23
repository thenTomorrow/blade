package it.blade.auth.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Parametri Argon2id condivisi dal login e dalla generazione offline degli hash. */
public final class Passwords {
    private Passwords() {}

    public static PasswordEncoder encoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
    }

    public static boolean valid(String password) {
        return password != null && !password.isEmpty() && password.length() <= 256;
    }
}