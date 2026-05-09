package muon.app.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import muon.app.common.secrets.SecretAliases;
import muon.app.common.settings.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class InfisicalClient {

    public static final String CLIENT_SECRET_ALIAS = SecretAliases.INFISICAL_CLIENT_SECRET;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public InfisicalClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
             new ObjectMapper(),
             REQUEST_TIMEOUT);
    }

    InfisicalClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this(httpClient, objectMapper, REQUEST_TIMEOUT);
    }

    InfisicalClient(HttpClient httpClient, ObjectMapper objectMapper, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout == null ? REQUEST_TIMEOUT : requestTimeout;
    }

    public String login(Settings settings, String clientSecret) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("clientId", settings.getInfisicalClientId());
        body.put("clientSecret", clientSecret);
        if (settings.getInfisicalOrganizationSlug() != null && !settings.getInfisicalOrganizationSlug().isBlank()) {
            body.put("organizationSlug", settings.getInfisicalOrganizationSlug());
        }

        HttpRequest request = requestBuilder(URI.create(trimTrailingSlash(settings.getInfisicalBaseUrl())
                + "/api/v1/auth/universal-auth/login"))
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
        ensureFolderPathExists(settings, accessToken, secretPath);
        HttpResponse<String> updateResponse = sendSecretRequestV4(settings, accessToken, secretPath, secretName, secretValue, "PATCH");
        if (updateResponse.statusCode() == 404) {
            HttpResponse<String> createResponse = sendSecretRequestV4(settings, accessToken, secretPath, secretName, secretValue, "POST");
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
                                                               .timeout(requestTimeout)
                                                               .header("Authorization", "Bearer " + accessToken)
                                                               .GET()
                                                               .build(),
                                                       HttpResponse.BodyHandlers.ofString());
        if (isRouteNotFound(response)) {
            throw new UnsupportedApiVersionException("Infisical latest secrets API is unavailable on this server");
        }
        if (response.statusCode() == 404) {
            return null;
        }
        ensureSuccess(response, "Infisical read secret failed");
        JsonNode secretValue = objectMapper.readTree(response.body()).path("secret").get("secretValue");
        return secretValue == null || secretValue.isNull() ? null : secretValue.asText();
    }

    private void ensureFolderPathExists(Settings settings, String accessToken, String secretPath) throws IOException, InterruptedException {
        String normalizedPath = normalizePath(secretPath);
        if ("/".equals(normalizedPath)) {
            return;
        }

        String currentPath = "/";
        for (String segment : splitPath(normalizedPath)) {
            if (!folderExists(settings, accessToken, currentPath, segment)) {
                createFolder(settings, accessToken, currentPath, segment);
            }
            currentPath = joinPath(currentPath, segment);
        }
    }

    private boolean folderExists(Settings settings, String accessToken, String parentPath, String folderName)
            throws IOException, InterruptedException {
        URI uri = URI.create(trimTrailingSlash(settings.getInfisicalBaseUrl())
                + "/api/v2/folders"
                + "?projectId=" + encode(settings.getInfisicalProjectId())
                + "&environment=" + encode(settings.getInfisicalEnvironment())
                + "&path=" + encode(parentPath)
                + "&recursive=false");
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri)
                                                               .timeout(requestTimeout)
                                                               .header("Authorization", "Bearer " + accessToken)
                                                               .GET()
                                                               .build(),
                                                       HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "Infisical list folders failed");

        JsonNode folders = objectMapper.readTree(response.body()).path("folders");
        if (!folders.isArray()) {
            return false;
        }
        String expectedPath = joinPath(parentPath, folderName);
        for (JsonNode folder : folders) {
            if (folderName.equals(folder.path("name").asText())) {
                return true;
            }
            if (expectedPath.equals(normalizeListedPath(folder.path("relativePath").asText(null)))) {
                return true;
            }
        }
        return false;
    }

    private void createFolder(Settings settings, String accessToken, String parentPath, String folderName)
            throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("projectId", settings.getInfisicalProjectId());
        body.put("environment", settings.getInfisicalEnvironment());
        body.put("name", folderName);
        body.put("path", normalizePath(parentPath));

        URI uri = URI.create(trimTrailingSlash(settings.getInfisicalBaseUrl()) + "/api/v2/folders");
        HttpResponse<String> response = httpClient.send(requestBuilder(uri)
                                                               .header("Authorization", "Bearer " + accessToken)
                                                               .header("Content-Type", "application/json")
                                                               .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                                                               .build(),
                                                       HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response, "Infisical create folder failed");
    }

    private HttpResponse<String> sendSecretRequestV4(Settings settings, String accessToken, String secretPath,
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
        return httpClient.send(requestBuilder(uri)
                                       .header("Authorization", "Bearer " + accessToken)
                                       .header("Content-Type", "application/json")
                                       .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                                       .build(),
                               HttpResponse.BodyHandlers.ofString());
    }

    private void ensureSuccess(HttpResponse<String> response, String message) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (isRouteNotFound(response)) {
                throw new UnsupportedApiVersionException(message + " HTTP " + response.statusCode() + ": " + response.body());
            }
            throw new IOException(message + " HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private boolean isRouteNotFound(HttpResponse<String> response) {
        if (response.statusCode() != 404) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode typeNode = root.get("type");
            return typeNode != null && "route_not_found".equals(typeNode.asText());
        } catch (Exception ignored) {
            return response.body() != null && response.body().contains("\"type\":\"route_not_found\"");
        }
    }

    private List<String> splitPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment != null && !segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private String joinPath(String parentPath, String segment) {
        String normalizedParent = normalizePath(parentPath);
        if ("/".equals(normalizedParent)) {
            return "/" + segment;
        }
        return normalizedParent + "/" + segment;
    }

    private String normalizeListedPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
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

    private HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(requestTimeout);
    }

    public static class UnsupportedApiVersionException extends IOException {

        public UnsupportedApiVersionException(String message) {
            super(message);
        }
    }
}
