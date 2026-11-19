package it.blade.auth.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.blade.auth.dto.AuthTokens;
import it.blade.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
    @Test
    void preservesTokenResponseAndUnauthorizedStatus() throws Exception {
        var auth = mock(AuthService.class);
        var tokens = new AuthTokens("access", "refresh", "Bearer", 300, 86_400);
        when(auth.login("blade", "test-password")).thenReturn(tokens);
        when(auth.refresh("refresh")).thenReturn(tokens);
        when(auth.refresh("rejected")).thenThrow(AuthService.AuthenticationRejected.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth)).build();

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"blade","password":"test-password"}
                                """))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.access_token").value("access"));
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refresh_token":"refresh"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").value("refresh"));
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refresh_token":"rejected"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
