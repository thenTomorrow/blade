package it.blade.auth.security;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Stampa l'hash Argon2id da salvare in {@code user.password_hash}.
 * Senza argomenti chiede la password nel terminale.
 */
public final class PasswordHashPrinter {
    private PasswordHashPrinter() {}

    public static void main(String[] args) throws java.io.IOException {
        var password = args.length == 1 ? args[0] : readPassword();
        if (!Passwords.valid(password) || password.length() < 15) {
            System.err.println("La password deve essere da 15 a 256 caratteri.");
            System.exit(2);
        }
        System.out.println(Passwords.encoder().encode(password));
    }

    private static String readPassword() throws java.io.IOException {
        var console = System.console();
        if (console != null) {
            var input = console.readPassword("Password: ");
            try {
                return input == null ? "" : new String(input);
            } finally {
                if (input != null) {
                    Arrays.fill(input, '\0');
                }
            }
        }
        System.err.print("Password: ");
        System.err.flush();
        var line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return line == null ? "" : line;
    }
}