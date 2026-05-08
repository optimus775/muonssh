package muon.app.vps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import muon.app.common.secrets.SecretAliases;
import muon.app.common.settings.Settings;
import muon.app.ui.components.session.SessionInfo;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public class VikunjaClient {

    public static final String API_TOKEN_ALIAS = SecretAliases.VIKUNJA_API_TOKEN;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VikunjaClient() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    VikunjaClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public long upsertPaymentTask(SessionInfo info, Settings settings, String token) throws IOException, InterruptedException {
        ObjectNode payload = buildPaymentTaskPayload(info, settings);
        URI uri;
        String method;
        if (info.getVikunjaTaskId() == null || info.getVikunjaTaskId() <= 0) {
            uri = URI.create(trimTrailingSlash(settings.getVikunjaBaseUrl())
                    + "/api/v1/projects/" + settings.getVikunjaProjectId() + "/tasks");
            method = "PUT";
        } else {
            payload.put("id", info.getVikunjaTaskId());
            uri = URI.create(trimTrailingSlash(settings.getVikunjaBaseUrl())
                    + "/api/v1/tasks/" + info.getVikunjaTaskId());
            method = "POST";
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Vikunja returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        JsonNode id = node.get("id");
        if (id == null || !id.canConvertToLong()) {
            throw new IOException("Vikunja response did not contain task id");
        }
        return id.asLong();
    }

    ObjectNode buildPaymentTaskPayload(SessionInfo info, Settings settings) {
        boolean hourlyBalance = "hourly_balance".equals(info.getBillingPeriodType());
        LocalDate dueDate = VpsDateFormat.parse(hourlyBalance ? info.getNextBalanceCheckDate() : info.getNextPaymentDate());
        ZonedDateTime dueAt = ZonedDateTime.of(dueDate, LocalTime.of(9, 0), ZoneId.systemDefault());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", hourlyBalance
                ? "Check provider balance: " + Objects.toString(info.getName(), info.getHost())
                : buildProviderTitle(info));
        payload.put("description", buildDescription(info));
        payload.put("project_id", settings.getVikunjaProjectId());
        payload.put("due_date", dueAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        payload.put("done", false);
        if (isMonthlyBilling(info)) {
            payload.put("repeat_mode", 1);
        }

        int reminderDays = Math.max(0, settings.getVikunjaReminderOffsetDays());
        ArrayNode reminders = objectMapper.createArrayNode();
        ObjectNode reminder = objectMapper.createObjectNode();
        reminder.put("relative_period", -reminderDays * 86400);
        reminder.put("relative_to", "due_date");
        reminders.add(reminder);
        payload.set("reminders", reminders);

        return payload;
    }

    private String buildDescription(SessionInfo info) {
        StringBuilder sb = new StringBuilder();
        appendBlock(sb, "Host", info.getHost());
        appendBlock(sb, "Provider", info.getProvider());
        appendBlock(sb, "Provider URL", info.getProviderUrl());
        appendBlock(sb, "Account/order", info.getAccountId());
        appendBlock(sb, "Billing mode", info.getBillingPeriodType());
        if ("hourly_balance".equals(info.getBillingPeriodType())) {
            appendBlock(sb, "Hourly rate", formatHourlyRate(info));
            appendBlock(sb, "Next balance check", VpsDateFormat.toDisplayDate(info.getNextBalanceCheckDate()));
        } else {
            appendBlock(sb, "Billing cycle", info.getBillingCycle());
            if (info.getBillingPeriodDays() > 0) {
                appendBlock(sb, "Billing period days", Integer.toString(info.getBillingPeriodDays()));
            }
            appendBlock(sb, "Price", formatPrice(info));
            appendBlock(sb, "Cancel by", VpsDateFormat.toDisplayDate(info.getCancelByDate()));
            appendBlock(sb, "Auto-pay", info.isAutoPay() ? "yes" : "no");
        }
        appendBlock(sb, "Status", info.getVpsStatus());
        appendBlock(sb, "Tags", info.getTags());
        appendBlock(sb, "External refs", info.getExternalRefs());
        if (info.getDescription() != null && !info.getDescription().isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(info.getDescription());
        }
        return sb.toString();
    }

    private String buildProviderTitle(SessionInfo info) {
        String provider = firstNonBlank(info.getProvider(), "Provider");
        String name = firstNonBlank(info.getName(), info.getHost());
        return provider + ": " + Objects.toString(name, "");
    }

    private String formatPrice(SessionInfo info) {
        if (info.getPrice() == null || info.getPrice().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(info.getPrice().trim()).toPlainString() + " " + Objects.toString(info.getCurrency(), "");
        } catch (NumberFormatException e) {
            return info.getPrice() + " " + Objects.toString(info.getCurrency(), "");
        }
    }

    private String formatHourlyRate(SessionInfo info) {
        if (info.getHourlyRate() == null || info.getHourlyRate().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(info.getHourlyRate().trim()).toPlainString() + " " + Objects.toString(info.getCurrency(), "");
        } catch (NumberFormatException e) {
            return info.getHourlyRate() + " " + Objects.toString(info.getCurrency(), "");
        }
    }

    private boolean isMonthlyBilling(SessionInfo info) {
        return info != null
                && !"hourly_balance".equals(info.getBillingPeriodType())
                && "monthly".equalsIgnoreCase(Objects.toString(info.getBillingCycle(), ""));
    }

    private void appendBlock(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Vikunja base URL is empty");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
