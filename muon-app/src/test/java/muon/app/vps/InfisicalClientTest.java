package muon.app.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import muon.app.common.settings.Settings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class InfisicalClientTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @Override
    protected void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    public void testCreateOrUpdateSecretCreatesMissingFolderUsingLatestApi() throws Exception {
        AtomicInteger folderListCalls = new AtomicInteger();
        AtomicInteger folderCreateCalls = new AtomicInteger();
        AtomicInteger secretPatchCalls = new AtomicInteger();
        AtomicInteger secretPostCalls = new AtomicInteger();
        AtomicReference<String> folderCreateBody = new AtomicReference<>();
        AtomicReference<String> secretPostBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v2/folders", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                folderListCalls.incrementAndGet();
                respondJson(exchange, 200, "{\"folders\":[]}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                folderCreateCalls.incrementAndGet();
                folderCreateBody.set(readBody(exchange));
                respondJson(exchange, 200, "{\"folder\":{\"name\":\"vps-manager\",\"relativePath\":\"/vps-manager\"}}");
                return;
            }
            respondJson(exchange, 405, "{}");
        });
        server.createContext("/api/v4/secrets/APP_STATE_JSON", exchange -> {
            if ("PATCH".equals(exchange.getRequestMethod())) {
                secretPatchCalls.incrementAndGet();
                respondJson(exchange, 404, "{\"message\":\"secret not found\"}");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                secretPostCalls.incrementAndGet();
                secretPostBody.set(readBody(exchange));
                respondJson(exchange, 200, "{\"secret\":{\"secretValue\":\"ok\"}}");
                return;
            }
            respondJson(exchange, 405, "{}");
        });
        server.start();

        InfisicalClient client = new InfisicalClient();
        Settings settings = createSettings();
        settings.setInfisicalBaseUrl("http://localhost:" + server.getAddress().getPort());

        client.createOrUpdateSecret(settings, "token", "/vps-manager", "APP_STATE_JSON", "{\"schema\":1}");

        assertEquals(1, folderListCalls.get());
        assertEquals(1, folderCreateCalls.get());
        assertEquals(1, secretPatchCalls.get());
        assertEquals(1, secretPostCalls.get());

        JsonNode folderBody = objectMapper.readTree(folderCreateBody.get());
        assertEquals("project-123", folderBody.path("projectId").asText());
        assertEquals("dev", folderBody.path("environment").asText());
        assertEquals("vps-manager", folderBody.path("name").asText());
        assertEquals("/", folderBody.path("path").asText());

        JsonNode secretBody = objectMapper.readTree(secretPostBody.get());
        assertEquals("project-123", secretBody.path("projectId").asText());
        assertEquals("dev", secretBody.path("environment").asText());
        assertEquals("/vps-manager", secretBody.path("secretPath").asText());
        assertEquals("{\"schema\":1}", secretBody.path("secretValue").asText());
        assertEquals("shared", secretBody.path("type").asText());
    }

    public void testReadSecretUsesLatestApi() throws Exception {
        AtomicReference<String> v4Query = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v4/secrets/APP_STATE_JSON", exchange -> {
            v4Query.set(exchange.getRequestURI().getQuery());
            respondJson(exchange, 200, "{\"secret\":{\"secretValue\":\"{\\\"updatedAt\\\":123}\"}}");
        });
        server.start();

        InfisicalClient client = new InfisicalClient();
        Settings settings = createSettings();
        settings.setInfisicalBaseUrl("http://localhost:" + server.getAddress().getPort());

        String secret = client.readSecret(settings, "token", "/vps-manager", "APP_STATE_JSON");

        assertEquals("{\"updatedAt\":123}", secret);
        assertNotNull(v4Query.get());
        assertTrue(v4Query.get().contains("projectId=project-123"));
        assertTrue(v4Query.get().contains("environment=dev"));
        assertTrue(v4Query.get().contains("secretPath=/vps-manager")
                           || v4Query.get().contains("secretPath=%2Fvps-manager"));
        assertTrue(v4Query.get().contains("includeImports=false"));
    }

    public void testReadSecretFailsOnUnsupportedServerVersion() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v4/secrets/APP_STATE_JSON", exchange ->
                respondJson(exchange, 404, "{\"type\":\"route_not_found\"}"));
        server.start();

        InfisicalClient client = new InfisicalClient();
        Settings settings = createSettings();
        settings.setInfisicalBaseUrl("http://localhost:" + server.getAddress().getPort());

        try {
            client.readSecret(settings, "token", "/vps-manager", "APP_STATE_JSON");
            fail("Expected UnsupportedApiVersionException");
        } catch (InfisicalClient.UnsupportedApiVersionException expected) {
            assertTrue(expected.getMessage().contains("latest secrets API"));
        }
    }

    public void testLoginTimesOutWhenServerStopsResponding() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/auth/universal-auth/login", exchange -> {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respondJson(exchange, 200, "{\"accessToken\":\"token\"}");
        });
        server.start();

        InfisicalClient client = new InfisicalClient(HttpClient.newHttpClient(),
                                                     new ObjectMapper(),
                                                     Duration.ofMillis(100));
        Settings settings = createSettings();
        settings.setInfisicalBaseUrl("http://localhost:" + server.getAddress().getPort());
        settings.setInfisicalClientId("client-123");

        try {
            client.login(settings, "client-secret");
            fail("Expected HttpTimeoutException");
        } catch (HttpTimeoutException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private Settings createSettings() {
        Settings settings = new Settings();
        settings.setInfisicalProjectId("project-123");
        settings.setInfisicalEnvironment("dev");
        settings.setInfisicalSecretBasePath("/vps-manager");
        return settings;
    }

    private void respondJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
