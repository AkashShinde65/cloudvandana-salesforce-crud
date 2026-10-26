package com.cloudvandana.salesforce_crud;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/salesforce")
public class SalesforceApiController {

    private final SalesforceService service;

    public SalesforceApiController(SalesforceService service) {
        this.service = service;
    }

    // Test whether the session can call Salesforce.
    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        return service.connectionStatus(session);
    }

    // Return the supported Salesforce objects and fields.
    @GetMapping("/objects")
    public List<Map<String, Object>> objects() {
        return service.listObjects();
    }

    // List the first 20 records, or the next page when a cursor is supplied.
    @GetMapping("/objects/{objectName}/records")
    public Map<String, Object> records(
            @PathVariable String objectName,
            @RequestParam(required = false) String cursor,
            HttpSession session) {

        return service.listRecords(objectName, cursor, session);
    }

    // Read a particular record.
    @GetMapping("/objects/{objectName}/records/{recordId}")
    public JsonNode getRecord(
            @PathVariable String objectName,
            @PathVariable String recordId,
            HttpSession session) {

        return service.getRecord(objectName, recordId, session);
    }

    // Create a record.
    @PostMapping("/objects/{objectName}/records")
    public ResponseEntity<JsonNode> create(
            @PathVariable String objectName,
            @RequestBody Map<String, Object> values,
            HttpSession session) {

        return ResponseEntity.status(201)
                .body(service.createRecord(objectName, values, session));
    }

    // Update a record.
    @PatchMapping("/objects/{objectName}/records/{recordId}")
    public Map<String, Boolean> update(
            @PathVariable String objectName,
            @PathVariable String recordId,
            @RequestBody Map<String, Object> values,
            HttpSession session) {

        service.updateRecord(objectName, recordId, values, session);
        return Map.of("updated", true);
    }

    // Delete a record.
    @DeleteMapping("/objects/{objectName}/records/{recordId}")
    public Map<String, Boolean> delete(
            @PathVariable String objectName,
            @PathVariable String recordId,
            HttpSession session) {

        service.deleteRecord(objectName, recordId, session);
        return Map.of("deleted", true);
    }
}