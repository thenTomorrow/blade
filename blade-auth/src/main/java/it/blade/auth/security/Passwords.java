package it.blade.auth.security;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    public static void main(String[] args) throws java.io.IOException {
        var console = System.console();
        char[] input = console == null ? null : console.readPassword("Password: ");
        try {
            String password = console == null
                    ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine()
                    : input == null ? null : new String(input);
            if (!valid(password) || password.length() < 15) {
                throw new IllegalArgumentException("Usare una password da 15 a 256 caratteri.");
            }
            System.out.println(encoder().encode(password));
        } finally {
            if (input != null) Arrays.fill(input, '\0');
        }
    }
}