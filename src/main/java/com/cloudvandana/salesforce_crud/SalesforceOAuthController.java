
package com.cloudvandana.salesforce_crud;

import org.springframework.security.web.csrf.CsrfToken;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@RestController
public class SalesforceOAuthController {

    @Value("${salesforce.oauth.authorize-url}")
    private String authorizeUrl;

    @Value("${salesforce.oauth.token-url}")
    private String tokenUrl;

    @Value("${salesforce.oauth.client-id}")
    private String clientId;

    @Value("${salesforce.oauth.client-secret}")
    private String clientSecret;

    @Value("${salesforce.oauth.redirect-uri}")
    private String redirectUri;

    private final RestClient restClient = RestClient.create();

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @GetMapping("/api/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "headerName", token.getHeaderName(),
                "token", token.getToken());
    }

    // Step 1: Redirect the user to Salesforce login
    @GetMapping("/api/auth/login")
    public ResponseEntity<Void> login(HttpSession session) throws Exception {

        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .build();
        }
        String state = randomUrlSafeValue(32);
        String verifier = randomUrlSafeValue(32);
        String challenge = createCodeChallenge(verifier);

        // Store these in the server-side session for callback validation
        session.setAttribute("sf_oauth_state", state);
        session.setAttribute("sf_pkce_verifier", verifier);

        var authorizationUri = UriComponentsBuilder
                .fromUriString(authorizeUrl)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "api refresh_token id profile email")
                .queryParam("state", state)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUri();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(authorizationUri)
                .build();
    }

    // Step 2: Salesforce redirects the user here after authorization
    @GetMapping("/api/auth/callback")
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpSession session) {

        if (error != null) {
            return textResponse(
                    HttpStatus.BAD_REQUEST,
                    "Salesforce authorization was not completed.");
        }

        String expectedState = (String) session.getAttribute("sf_oauth_state");

        String verifier = (String) session.getAttribute("sf_pkce_verifier");

        // Reject callbacks that do not match the login session
        if (expectedState == null || state == null || verifier == null
                || !MessageDigest.isEqual(
                        expectedState.getBytes(StandardCharsets.UTF_8),
                        state.getBytes(StandardCharsets.UTF_8))) {

            return textResponse(
                    HttpStatus.BAD_REQUEST,
                    "Invalid OAuth state. Please start login again.");
        }

        if (code == null || code.isBlank()) {
            return textResponse(
                    HttpStatus.BAD_REQUEST,
                    "Salesforce did not return an authorization code.");
        }

        if (clientSecret == null || clientSecret.isBlank()) {
            return textResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Client secret is not configured on the backend.");
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("redirect_uri", redirectUri);
            form.add("code_verifier", verifier);

            String responseJson = restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode tokenData = objectMapper.readTree(responseJson);
            String accessToken = tokenData.path("access_token").asText("");

            if (accessToken.isBlank()) {
                return textResponse(
                        HttpStatus.BAD_GATEWAY,
                        "Salesforce did not return an access token.");
            }

            // Keep tokens on the backend, not in the browser response
            session.setAttribute("sf_access_token", accessToken);
            session.setAttribute(
                    "sf_instance_url",
                    tokenData.path("instance_url").asText(""));

            String refreshToken = tokenData.path("refresh_token").asText("");

            if (!refreshToken.isBlank()) {
                session.setAttribute("sf_refresh_token", refreshToken);
            }

            session.removeAttribute("sf_oauth_state");
            session.removeAttribute("sf_pkce_verifier");

            return textResponse(
                    HttpStatus.OK,
                    "Salesforce login successful! Your session is connected.");

        } catch (RestClientResponseException ex) {
            return textResponse(
                    HttpStatus.BAD_GATEWAY,
                    "Salesforce rejected the token exchange. Check the OAuth settings.");
        } catch (Exception ex) {
            return textResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Login failed. Check the VS Code terminal for details.");
        }
    }

    private String randomUrlSafeValue(int byteCount) {
        byte[] bytes = new byte[byteCount];
        new SecureRandom().nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String createCodeChallenge(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(hash);
    }

    private ResponseEntity<String> textResponse(
            HttpStatus status, String message) {

        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .body(message);
    }
}