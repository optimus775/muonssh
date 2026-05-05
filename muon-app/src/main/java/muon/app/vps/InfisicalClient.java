package muon.app.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import muon.app.common.settings.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class InfisicalClient {

    public static final String CLIENT_SECRET_ALIAS = "vps-ledger.infisical.client-secret";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public InfisicalClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    InfisicalClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public String login(Settings settings, String clientSecret) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("clientId", settings.getInfisicalClientId());
        body.put("clientSecret", clientSecret);
        if (settings.getInfisicalOrganizationSlug() != null && !settings.getInfisicalOrganizationSlug().isBlank()) {
            body.put("organizationSlug", settings.getInfisicalOrganizationSlug());
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(settings.getInfisicalBaseUrl()) + "/api/v1/auth/universal-auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "Infisical login failed");
        JsonNode token = objectMapper.readTree(response.body()).get("accessToken");
        if (token == null || token.asText().isBlank()) {
            throw new IOException("Infisical login response did not contain accessToken");
        }
        return token.asText();
    }

    public void createOrUpdateSecret(Settings settings, String accessToken, String secretPath, String secretName, String secretValue)
            throws IOException, InterruptedException {
        HttpResponse<String> updateResponse = sendSecretRequest(settings, accessToken, secretPath, secretName, secretValue, "PATCH");
        if (updateResponse.statusCode() == 404) {
            HttpResponse<String> createResponse = sendSecretRequest(settings, accessToken, secretPath, secretName, secretValue, "POST");
            ensureSuccess(createResponse, "Infisical create secret failed");
            return;
        }
        ensureSuccess(updateResponse, "Infisical update secret failed");
    }

    public String readSecret(Settings settings, String accessToken, String secretPath, String secretName)
            throws IOException, InterruptedException {
        URI uri = URI.create(trimTrailingSlash(settings.getInfisicalBaseUrl())
                + "/api/v4/secrets/" + encode(secretName)
                + "?projectId=" + encode(settings.getInfisicalProjectId())
                + "&environment=" + encode(settings.getInfisicalEnvironment())
                + "&secretPath=" + encode(normalizePath(secretPath))
                + "&type=shared&viewSecretValue=true&expandSecretReferences=false&includeImports=false");
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri)
                                                               .header("Authorization", "Bearer " + accessToken)
                                                               .GET()
                                                               .build(),
                                                       HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        ensureSuccess(response, "Infisical read secret failed");
        JsonNode secretValue = objectMapper.readTree(response.body()).path("secret").get("secretValue");
        return secretValue == null || secretValue.isNull() ? null : secretValue.asText();
    }

    private HttpResponse<String> sendSecretRequest(Settings settings, String accessToken, String secretPath,
                                                   String secretName, String secretValue, String method)
            throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("projectId", settings.getInfisicalProjectId());
        body.put("environment", settings.getInfisicalEnvironment());
        body.put("secretValue", secretValue);
        body.put("secretPath", normalizePath(secretPath));
        body.put("secretComment", "Managed by VPS Ledger");
        body.put("skipMultilineEncoding", true);
        body.put("type", "shared");

        URI uri = URI.create(trimTrailingSlash(settings.getInfisicalBaseUrl()) + "/api/v4/secrets/" + encode(secretName));
        return httpClient.send(HttpRequest.newBuilder(uri)
                                       .header("Authorization", "Bearer " + accessToken)
                                       .header("Content-Type", "application/json")
                                       .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                                       .build(),
                               HttpResponse.BodyHandlers.ofString());
    }

    private void ensureSuccess(HttpResponse<String> response, String message) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(message + " HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Infisical base URL is empty");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
