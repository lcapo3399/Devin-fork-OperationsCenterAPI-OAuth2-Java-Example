package com.deere.isg.examples;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import kong.unirest.core.MockClient;
import kong.unirest.core.Unirest;
import kong.unirest.core.json.JSONArray;
import kong.unirest.core.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Base64;

import static kong.unirest.core.HttpMethod.GET;
import static kong.unirest.core.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ApplicationTest {

    private Application application;
    private MockClient mock;

    private static String fakeJwt(String subject) {
        String header = Base64.getEncoder().encodeToString("{\"alg\":\"RS256\"}".getBytes());
        JSONObject payload = new JSONObject();
        payload.put("sub", subject);
        payload.put("iss", "https://test.example.com");
        String body = Base64.getEncoder().encodeToString(payload.toString().getBytes());
        return header + "." + body + ".fakesignature";
    }

    @BeforeEach
    void setUp() {
        mock = MockClient.register();
        application = new Application();
    }

    @AfterEach
    void tearDown() {
        Unirest.config().reset();
    }

    private Settings getSettings() throws Exception {
        Field field = Application.class.getDeclaredField("settings");
        field.setAccessible(true);
        return (Settings) field.get(application);
    }

    @Test
    void testIndexRoute() {
        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("John Deere OAuth2 Example"));
        });
    }

    @Test
    void testStartOIDCRedirects() throws Exception {
        Settings settings = getSettings();
        String wellKnownUrl = settings.wellKnown;
        String authEndpoint = "https://test.example.com/authorize";

        JSONObject wellKnownResponse = new JSONObject();
        wellKnownResponse.put("authorization_endpoint", authEndpoint);
        wellKnownResponse.put("token_endpoint", "https://test.example.com/token");

        mock.expect(GET, wellKnownUrl).thenReturn(wellKnownResponse.toString());

        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            okhttp3.RequestBody formBody = new okhttp3.FormBody.Builder()
                    .add("clientId", "testClientId")
                    .add("clientSecret", "testClientSecret")
                    .add("wellKnown", wellKnownUrl)
                    .add("callbackUrl", "http://localhost:9090/callback")
                    .add("scopes", "openid profile")
                    .add("state", "testState")
                    .build();

            okhttp3.OkHttpClient noRedirectClient = new okhttp3.OkHttpClient.Builder()
                    .followRedirects(false)
                    .build();

            String baseUrl = "http://localhost:" + server.port();
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(baseUrl + "/")
                    .post(formBody)
                    .build();

            try (okhttp3.Response response = noRedirectClient.newCall(request).execute()) {
                assertEquals(302, response.code());
                String location = response.header("Location");
                assertNotNull(location);
                assertTrue(location.contains(authEndpoint));
                assertTrue(location.contains("client_id=testClientId"));
            }
        });

        assertEquals("testClientId", settings.clientId);
        assertEquals("testClientSecret", settings.clientSecret);
    }

    @Test
    void testCallbackWithError() {
        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/callback?error=access_denied&error_description=User+denied+access");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("User denied access"));
        });
    }

    @Test
    void testCallbackSuccess() throws Exception {
        Settings settings = getSettings();
        String wellKnownUrl = settings.wellKnown;
        String tokenEndpoint = "https://test.example.com/token";
        String jwt = fakeJwt("callbackUser");

        JSONObject wellKnownResponse = new JSONObject();
        wellKnownResponse.put("authorization_endpoint", "https://test.example.com/authorize");
        wellKnownResponse.put("token_endpoint", tokenEndpoint);

        JSONObject tokenResponse = new JSONObject();
        tokenResponse.put("access_token", jwt);
        tokenResponse.put("refresh_token", "newRefreshToken");
        tokenResponse.put("id_token", "newIdToken");
        tokenResponse.put("expires_in", 3600);

        JSONObject orgResponse = new JSONObject();
        orgResponse.put("values", new JSONArray());

        mock.expect(GET, wellKnownUrl).thenReturn(wellKnownResponse.toString());
        mock.expect(POST, tokenEndpoint).thenReturn(tokenResponse.toString());
        mock.expect(GET, settings.apiUrl + "/organizations").thenReturn(orgResponse.toString());

        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/callback?code=testCode");
            assertEquals(200, response.code());
        });

        assertEquals(jwt, settings.accessToken);
        assertEquals("newRefreshToken", settings.refreshToken);
    }

    @Test
    void testRefreshAccessToken() throws Exception {
        Settings settings = getSettings();
        settings.clientId = "testClient";
        settings.clientSecret = "testSecret";
        settings.refreshToken = "existingRefreshToken";
        String wellKnownUrl = settings.wellKnown;
        String tokenEndpoint = "https://test.example.com/token";
        String jwt = fakeJwt("refreshedUser");

        JSONObject wellKnownResponse = new JSONObject();
        wellKnownResponse.put("authorization_endpoint", "https://test.example.com/authorize");
        wellKnownResponse.put("token_endpoint", tokenEndpoint);

        JSONObject refreshResponse = new JSONObject();
        refreshResponse.put("access_token", jwt);
        refreshResponse.put("refresh_token", "refreshedRefreshToken");
        refreshResponse.put("id_token", "refreshedIdToken");
        refreshResponse.put("expires_in", 7200);

        mock.expect(GET, wellKnownUrl).thenReturn(wellKnownResponse.toString());
        mock.expect(POST, tokenEndpoint).thenReturn(refreshResponse.toString());

        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/refresh-access-token");
            assertEquals(200, response.code());
        });

        assertEquals(jwt, settings.accessToken);
        assertEquals("refreshedRefreshToken", settings.refreshToken);
    }

    @Test
    void testCallTheApi() throws Exception {
        Settings settings = getSettings();
        String jwt = fakeJwt("apiTestUser");
        settings.accessToken = jwt;

        JSONObject apiResponse = new JSONObject();
        apiResponse.put("data", "testData");

        mock.expect(GET, "https://example.com/api/test").thenReturn(apiResponse.toString());

        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            okhttp3.RequestBody formBody = new okhttp3.FormBody.Builder()
                    .add("url", "https://example.com/api/test")
                    .build();
            var response = client.request("/call-api", builder ->
                    builder.post(formBody)
            );
            assertEquals(200, response.code());
        });

        assertNotNull(settings.apiResponse);
        assertTrue(settings.apiResponse.contains("testData"));
    }

    @Test
    void testGetLocationFromMetaCaching() throws Exception {
        Settings settings = getSettings();
        String wellKnownUrl = settings.wellKnown;

        JSONObject wellKnownResponse = new JSONObject();
        wellKnownResponse.put("authorization_endpoint", "https://test.example.com/authorize");
        wellKnownResponse.put("token_endpoint", "https://test.example.com/token");

        mock.expect(GET, wellKnownUrl).thenReturn(wellKnownResponse.toString());

        Javalin app = application.createApp();

        settings.clientId = "cacheTestClient";
        settings.clientSecret = "cacheTestSecret";
        settings.refreshToken = "cacheRefreshToken";

        String jwt = fakeJwt("cachedUser");
        JSONObject refreshResponse = new JSONObject();
        refreshResponse.put("access_token", jwt);
        refreshResponse.put("refresh_token", "cachedRefreshToken");
        refreshResponse.put("id_token", "cachedIdToken");
        refreshResponse.put("expires_in", 3600);

        mock.expect(POST, "https://test.example.com/token").thenReturn(refreshResponse.toString());

        JavalinTest.test(app, (server, client) -> {
            client.get("/refresh-access-token");
            mock.expect(POST, "https://test.example.com/token").thenReturn(refreshResponse.toString());
            client.get("/refresh-access-token");
        });

        mock.assertThat(GET, wellKnownUrl).wasInvokedTimes(1);
    }

    @Test
    void testNeedsOrganizationAccessWithConnections() throws Exception {
        Settings settings = getSettings();
        settings.clientId = "testClient";
        settings.clientSecret = "testSecret";
        String wellKnownUrl = settings.wellKnown;
        String tokenEndpoint = "https://test.example.com/token";
        String jwt = fakeJwt("orgUser");

        JSONObject wellKnownResponse = new JSONObject();
        wellKnownResponse.put("authorization_endpoint", "https://test.example.com/authorize");
        wellKnownResponse.put("token_endpoint", tokenEndpoint);

        JSONObject tokenResponse = new JSONObject();
        tokenResponse.put("access_token", jwt);
        tokenResponse.put("refresh_token", "orgRefreshToken");
        tokenResponse.put("id_token", "orgIdToken");
        tokenResponse.put("expires_in", 3600);

        JSONObject connectionLink = new JSONObject();
        connectionLink.put("rel", "connections");
        connectionLink.put("uri", "https://connections.deere.com/connections/setup");

        JSONObject org = new JSONObject();
        org.put("links", new JSONArray().put(connectionLink));

        JSONObject orgResponse = new JSONObject();
        orgResponse.put("values", new JSONArray().put(org));

        mock.expect(GET, wellKnownUrl).thenReturn(wellKnownResponse.toString());
        mock.expect(POST, tokenEndpoint).thenReturn(tokenResponse.toString());
        mock.expect(GET, settings.apiUrl + "/organizations").thenReturn(orgResponse.toString());

        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            okhttp3.OkHttpClient noRedirectClient = new okhttp3.OkHttpClient.Builder()
                    .followRedirects(false)
                    .build();
            String baseUrl = "http://localhost:" + server.port();
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(baseUrl + "/callback?code=testCode")
                    .build();
            try (okhttp3.Response response = noRedirectClient.newCall(request).execute()) {
                assertTrue(response.code() == 200 || response.code() == 302);
            }
        });
    }

    @Test
    void testRenderError() {
        Javalin app = application.createApp();
        JavalinTest.test(app, (server, client) -> {
            var response = client.get("/callback?error=server_error&error_description=Something+went+wrong");
            assertEquals(200, response.code());
            String body = response.body().string();
            assertTrue(body.contains("Something went wrong"));
        });
    }
}
