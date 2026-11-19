package it.blade.auth.controller;

import it.blade.auth.dto.AuthTokens;
import it.blade.auth.service.AuthService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
class AuthController {
    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    ResponseEntity<AuthTokens> login(@RequestBody Login request) {
        try {
            return noStore(auth.login(request.username(), request.password()));
        } catch (AuthService.AuthenticationRejected exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthTokens> refresh(@RequestBody Refresh request) {
        try {
            return noStore(auth.refresh(request.refresh_token()));
        } catch (AuthService.AuthenticationRejected exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static ResponseEntity<AuthTokens> noStore(AuthTokens tokens) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    record Login(String username, String password) {}
    record Refresh(String refresh_token) {}
}
