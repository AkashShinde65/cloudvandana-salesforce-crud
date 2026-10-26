package com.cloudvandana.salesforce_crud;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class SalesforceService {

    private static final String API = "/services/data/latest";
    private static final int PAGE_SIZE = 20;

    private static final Pattern SF_ID = Pattern.compile("[A-Za-z0-9]{15,18}");

    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}" +
                    "(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})");

    private final RestClient restClient = RestClient.create();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private record ObjectSpec(
            List<String> fields,
            List<String> createFields,
            List<String> updateFields) {
    }

    // Only these five objects can be accessed by this application.
    private static final Map<String, ObjectSpec> OBJECTS = Map.of(
            "Account", new ObjectSpec(
                    List.of("Name", "Phone", "Industry", "Website", "Type"),
                    List.of("Name", "Phone", "Industry", "Website", "Type"),
                    List.of("Name", "Phone", "Industry", "Website", "Type")),
            "Opportunity", new ObjectSpec(
                    List.of("Name", "StageName", "Amount", "CloseDate", "Probability"),
                    List.of("Name", "StageName", "Amount", "CloseDate"),
                    List.of("Name", "StageName", "Amount", "CloseDate")),
            "Lead", new ObjectSpec(
                    List.of("FirstName", "LastName", "Company", "Email", "Status", "Phone"),
                    List.of("FirstName", "LastName", "Company", "Email", "Status", "Phone"),
                    List.of("FirstName", "LastName", "Company", "Email", "Status", "Phone")),
            "Contact", new ObjectSpec(
                    List.of("FirstName", "LastName", "Email", "Phone", "Title"),
                    List.of("FirstName", "LastName", "Email", "Phone", "Title", "AccountId"),
                    List.of("FirstName", "LastName", "Email", "Phone", "Title", "AccountId")),
            "Case", new ObjectSpec(
                    List.of("CaseNumber", "Subject", "Status", "Priority", "Origin"),
                    List.of("Subject", "Status", "Priority", "Origin", "Type", "Description"),
                    List.of("Subject", "Status", "Priority", "Origin", "Type", "Description")));

    // Verify the current session by calling Salesforce.
    public Map<String, Object> connectionStatus(HttpSession session) {
        String response = request(
                session,
                HttpMethod.GET,
                API + "/sobjects/",
                null);

        JsonNode json = parseJson(response);
        int count = json.path("sobjects").size();

        return Map.of(
                "connected", true,
                "message", "Salesforce API connection successful",
                "availableObjectCount", count);
    }

    // Return the five objects and their configured fields.
    public List<Map<String, Object>> listObjects() {
        List<Map<String, Object>> result = new ArrayList<>();

        OBJECTS.forEach((name, spec) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("fields", spec.fields());
            item.put("createFields", spec.createFields());
            item.put("updateFields", spec.updateFields());
            result.add(item);
        });

        return result;
    }

    // Retrieve records in pages of 20.
    public Map<String, Object> listRecords(
            String objectName,
            String cursor,
            HttpSession session) {

        ObjectSpec spec = getSpec(objectName);

        String where = "";

        if (cursor != null && !cursor.isBlank()) {
            Cursor decoded = decodeCursor(cursor);

            where = " WHERE (CreatedDate < " + decoded.createdDate()
                    + " OR (CreatedDate = " + decoded.createdDate()
                    + " AND Id < '" + decoded.id() + "'))";
        }

        String soql = "SELECT Id, CreatedDate, "
                + String.join(", ", spec.fields())
                + " FROM " + objectName
                + where
                + " ORDER BY CreatedDate DESC, Id DESC"
                + " LIMIT " + (PAGE_SIZE + 1);

        JsonNode response = parseJson(query(session, soql));
        JsonNode rows = response.path("records");

        boolean hasMore = rows.size() > PAGE_SIZE;
        int count = Math.min(rows.size(), PAGE_SIZE);

        List<ObjectNode> records = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            records.add(projectRecord(rows.get(i), spec));
        }

        String nextCursor = null;

        if (hasMore) {
            JsonNode lastVisible = rows.get(PAGE_SIZE - 1);

            nextCursor = encodeCursor(
                    lastVisible.path("CreatedDate").asText(),
                    lastVisible.path("Id").asText());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectName", objectName);
        result.put("fields", spec.fields());
        result.put("records", records);
        result.put("hasMore", hasMore);
        result.put("nextCursor", nextCursor);

        return result;
    }

    // Retrieve one record by Salesforce ID.
    public JsonNode getRecord(
            String objectName,
            String recordId,
            HttpSession session) {

        ObjectSpec spec = getSpec(objectName);
        validateId(recordId);

        String soql = "SELECT Id, "
                + String.join(", ", spec.fields())
                + " FROM " + objectName
                + " WHERE Id = '" + recordId + "' LIMIT 1";

        JsonNode rows = parseJson(query(session, soql)).path("records");

        if (rows.size() == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Record not found");
        }

        return projectRecord(rows.get(0), spec);
    }

    // Create a record.
    public JsonNode createRecord(
            String objectName,
            Map<String, Object> values,
            HttpSession session) {

        ObjectSpec spec = getSpec(objectName);
        validateFields(values, spec.createFields());

        String body = request(
                session,
                HttpMethod.POST,
                API + "/sobjects/" + objectName + "/",
                values);

        return parseJson(body);
    }

    // Update an existing record.
    public void updateRecord(
            String objectName,
            String recordId,
            Map<String, Object> values,
            HttpSession session) {

        ObjectSpec spec = getSpec(objectName);
        validateId(recordId);
        validateFields(values, spec.updateFields());

        request(
                session,
                HttpMethod.PATCH,
                API + "/sobjects/" + objectName + "/" + recordId,
                values);
    }

    // Delete an existing record.
    public void deleteRecord(
            String objectName,
            String recordId,
            HttpSession session) {

        getSpec(objectName);
        validateId(recordId);

        request(
                session,
                HttpMethod.DELETE,
                API + "/sobjects/" + objectName + "/" + recordId,
                null);
    }

    private ObjectSpec getSpec(String objectName) {
        ObjectSpec spec = OBJECTS.get(objectName);

        if (spec == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported object. Use Account, Opportunity, Lead, Contact, or Case.");
        }

        return spec;
    }

    private ObjectNode projectRecord(JsonNode row, ObjectSpec spec) {
        ObjectNode output = mapper.createObjectNode();
        output.put("Id", row.path("Id").asText());

        for (String field : spec.fields()) {
            JsonNode value = row.get(field);

            if (value == null || value.isNull()) {
                output.putNull(field);
            } else {
                output.set(field, value.deepCopy());
            }
        }

        return output;
    }

    private void validateFields(
            Map<String, Object> values,
            List<String> allowedFields) {

        if (values == null || values.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Provide at least one field value");
        }

        for (String field : values.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Field is not allowed for this operation: " + field);
            }
        }
    }

    private void validateId(String id) {
        if (id == null || !SF_ID.matcher(id).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid Salesforce record ID");
        }
    }

    private String query(HttpSession session, String soql) {
        String encodedQuery = URLEncoder.encode(
                soql, StandardCharsets.UTF_8);

        return request(
                session,
                HttpMethod.GET,
                API + "/query?q=" + encodedQuery,
                null);
    }

    private String request(
            HttpSession session,
            HttpMethod method,
            String path,
            Object body) {

        String token = (String) session.getAttribute("sf_access_token");

        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Salesforce session missing. Please log in again.");
        }

        URI uri = URI.create(instanceUrl(session) + path);

        RestClient.RequestBodySpec req = restClient
                .method(method)
                .uri(uri)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                });

        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON);
            req.body(body);
        }

        try {
            String response = req.retrieve().body(String.class);
            return response == null ? "" : response;

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();

            if (status == 401) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Salesforce authorization expired. Please log in again.");
            }

            if (status == 403) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Your Salesforce user lacks permission for this operation.");
            }

            if (status == 400) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Salesforce rejected the request. Check required fields and picklist values.");
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Salesforce API returned HTTP " + status);
        }
    }

    private String instanceUrl(HttpSession session) {
        String value = (String) session.getAttribute("sf_instance_url");

        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Salesforce session missing. Please log in again.");
        }

        URI uri;

        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Invalid Salesforce instance URL");
        }

        String host = uri.getHost();

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !(host.equals("salesforce.com")
                        || host.endsWith(".salesforce.com")
                        || host.endsWith(".force.com"))) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Invalid Salesforce instance URL");
        }

        return value.replaceAll("/+$", "");
    }

    private JsonNode parseJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not parse the Salesforce API response");
        }
    }

    private record Cursor(String createdDate, String id) {
    }

    private String encodeCursor(String createdDate, String id) {
        String value = createdDate + "|" + id;

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encoded) {
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);

            int separator = decoded.lastIndexOf('|');

            if (separator < 0) {
                throw new IllegalArgumentException();
            }

            String date = decoded.substring(0, separator)
                    .replace("+0000", "Z");

            String id = decoded.substring(separator + 1);

            if (!DATE_TIME.matcher(date).matches()
                    || !SF_ID.matcher(id).matches()) {
                throw new IllegalArgumentException();
            }

            return new Cursor(date, id);

        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid pagination cursor");
        }
    }
}