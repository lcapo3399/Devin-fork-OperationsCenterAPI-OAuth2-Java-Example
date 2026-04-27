package com.deere.isg.examples;

import kong.unirest.core.MockClient;
import kong.unirest.core.Unirest;
import kong.unirest.core.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static kong.unirest.core.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.*;

class ApiTest {

    private Api api;
    private MockClient mock;

    @BeforeEach
    void setUp() {
        mock = MockClient.register();
        api = new Api();
    }

    @AfterEach
    void tearDown() {
        Unirest.config().reset();
    }

    @Test
    void testGetSetsAuthorizationHeader() {
        mock.expect(GET, "https://example.com/resource")
                .thenReturn("{\"key\": \"value\"}");

        api.get("myToken", "https://example.com/resource");

        mock.assertThat(GET, "https://example.com/resource")
                .hadHeader("authorization", "Bearer myToken");
    }

    @Test
    void testGetSetsAcceptHeader() {
        mock.expect(GET, "https://example.com/resource")
                .thenReturn("{\"key\": \"value\"}");

        api.get("myToken", "https://example.com/resource");

        mock.assertThat(GET, "https://example.com/resource")
                .hadHeader("Accept", "application/vnd.deere.axiom.v3+json");
    }

    @Test
    void testGetReturnsJsonObject() {
        mock.expect(GET, "https://example.com/resource")
                .thenReturn("{\"name\": \"John\", \"age\": 30}");

        JSONObject result = api.get("myToken", "https://example.com/resource");

        assertNotNull(result);
        assertEquals("John", result.getString("name"));
        assertEquals(30, result.getInt("age"));
    }

    @Test
    void testGetWithErrorStatusAndInterceptor() {
        Unirest.config().interceptor(new LoggingInterceptor());
        mock.expect(GET, "https://example.com/bad")
                .thenReturn("{\"error\": \"not found\"}")
                .withStatus(404);

        assertThrows(RequestException.class, () -> api.get("myToken", "https://example.com/bad"));
    }

    @Test
    void testGetWithSuccessStatus() {
        mock.expect(GET, "https://example.com/ok")
                .thenReturn("{\"status\": \"ok\"}")
                .withStatus(200);

        JSONObject result = api.get("myToken", "https://example.com/ok");
        assertNotNull(result);
        assertEquals("ok", result.getString("status"));
    }
}
