package com.deere.isg.examples;

import io.javalin.http.Context;
import kong.unirest.core.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsTest {

    private Settings settings;

    @Mock
    private Context context;

    @BeforeEach
    void setUp() {
        settings = new Settings();
    }

    @Test
    void testDefaultValues() {
        assertTrue(settings.wellKnown.contains("signin.johndeere.com"));
        assertEquals("https://sandboxapi.deere.com/platform", settings.apiUrl);
        assertEquals("openid profile offline_access ag1 eq1", settings.scopes);
        assertNotNull(settings.state);
        assertFalse(settings.state.isEmpty());
        assertEquals("http://localhost:9090/callback", settings.callbackUrl);
    }

    @Test
    void testGetBasicAuthHeader() {
        settings.clientId = "testId";
        settings.clientSecret = "testSecret";

        String result = settings.getBasicAuthHeader();
        String expected = Base64.getEncoder().encodeToString("testId:testSecret".getBytes());

        assertEquals(expected, result);
    }

    @Test
    void testUpdateTokenInfo() {
        JSONObject tokenResponse = new JSONObject();
        tokenResponse.put("access_token", "myAccessToken");
        tokenResponse.put("refresh_token", "myRefreshToken");
        tokenResponse.put("id_token", "myIdToken");
        tokenResponse.put("expires_in", 3600L);

        settings.updateTokenInfo(tokenResponse);

        assertEquals("myAccessToken", settings.accessToken);
        assertEquals("myRefreshToken", settings.refreshToken);
        assertEquals("myIdToken", settings.idToken);
        assertEquals(3600L, settings.exp);
    }

    @Test
    void testUpdateTokenInfoWithMissingFields() {
        JSONObject tokenResponse = new JSONObject();

        settings.updateTokenInfo(tokenResponse);

        assertEquals("", settings.idToken);
        assertEquals("", settings.accessToken);
        assertEquals("", settings.refreshToken);
        assertEquals(0L, settings.exp);
    }

    @Test
    void testGetAccessTokenDetails() {
        JSONObject payload = new JSONObject();
        payload.put("sub", "user123");
        payload.put("iss", "https://example.com");
        String encodedPayload = Base64.getEncoder().encodeToString(payload.toString().getBytes());
        settings.accessToken = "header." + encodedPayload + ".signature";

        String result = settings.getAccessTokenDetails();

        assertNotNull(result);
        assertTrue(result.contains("user123"));
        assertTrue(result.contains("https://example.com"));
    }

    @Test
    void testGetAccessTokenDetailsWithNullToken() {
        settings.accessToken = null;
        assertNull(settings.getAccessTokenDetails());

        settings.accessToken = "";
        assertNull(settings.getAccessTokenDetails());
    }

    @Test
    void testGetExpiration() {
        settings.exp = 3600L;

        String result = settings.getExpiration();

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void testGetExpirationWithNull() {
        settings.exp = null;
        assertNull(settings.getExpiration());
    }

    @Test
    void testPopulate() {
        when(context.formParam("clientId")).thenReturn("myClientId");
        when(context.formParam("clientSecret")).thenReturn("myClientSecret");
        when(context.formParam("wellKnown")).thenReturn("https://example.com/.well-known");
        when(context.formParam("callbackUrl")).thenReturn("http://localhost:9090/callback");
        when(context.formParam("scopes")).thenReturn("openid profile");
        when(context.formParam("state")).thenReturn("myState123");

        settings.populate(context);

        assertEquals("myClientId", settings.clientId);
        assertEquals("myClientSecret", settings.clientSecret);
        assertEquals("https://example.com/.well-known", settings.wellKnown);
        assertEquals("http://localhost:9090/callback", settings.callbackUrl);
        assertEquals("openid profile", settings.scopes);
        assertEquals("myState123", settings.state);
    }
}
