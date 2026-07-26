package com.behsazan.schemaforge.specification.validation.spelling;

import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the behavior and regression expectations of Language Tool Spell Check Service.
 *
 * <p>This test documents expected behavior and protects against regression.</p>
 *
 * @since 4.1
 */
class LanguageToolSpellCheckServiceTest {

    @Test
    void convertsSnakeCaseToLowercaseWordsAndReturnsSuggestion() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(requestBody, """
                {"matches":[{
                  "message":"Possible spelling mistake found.",
                  "offset":0,
                  "length":7,
                  "replacements":[{"value":"province"}],
                  "rule":{"issueType":"misspelling","category":{"id":"TYPOS"}}
                }]}
                """);
        try {
            SpellCheckProperties properties = properties(server);
            LanguageToolSpellCheckService service =
                    new LanguageToolSpellCheckService(properties, new ObjectMapper());

            List<SpellingError> errors = service.check("PROVINC_NAME");

            assertEquals(1, errors.size());
            assertEquals("PROVINC", errors.getFirst().word());
            assertEquals("province", errors.getFirst().suggestions().getFirst().value());
            assertTrue(requestBody.get().contains("text=provinc+name"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsServiceFailureOnceWhenFailOpenIsEnabled() {
        SpellCheckProperties properties = new SpellCheckProperties();
        properties.setEnabled(true);
        properties.setFailOpen(true);
        properties.setEndpoint("http://127.0.0.1:1/v2/check");
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setRequestTimeout(Duration.ofMillis(100));

        LanguageToolSpellCheckService service =
                new LanguageToolSpellCheckService(properties, new ObjectMapper());

        List<SpellingError> first = service.check("PROVINC_NAME");
        List<SpellingError> second = service.check("ANOTHER_NAME");

        assertEquals(1, first.size());
        assertTrue(first.getFirst().serviceFailure());
        assertTrue(second.isEmpty());
    }

    private static SpellCheckProperties properties(HttpServer server) {
        SpellCheckProperties properties = new SpellCheckProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/check");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        properties.setMaximumSuggestions(3);
        return properties;
    }

    private static HttpServer startServer(
            AtomicReference<String> requestBody,
            String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/check", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }



    @Test
    void ignoresCapitalizationOnlySuggestion() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = startServer(requestBody, """
            {
              "matches": [
                {
                  "message": "Possible spelling mistake found.",
                  "offset": 9,
                  "length": 7,
                  "replacements": [
                    { "value": "English" }
                  ],
                  "rule": {
                    "id": "MORFOLOGIK_RULE_EN_US",
                    "issueType": "misspelling",
                    "category": {
                      "id": "TYPOS"
                    }
                  }
                }
              ]
            }
            """);

        try {
            SpellCheckProperties properties = properties(server);

            LanguageToolSpellCheckService service =
                    new LanguageToolSpellCheckService(
                            properties,
                            new ObjectMapper());

            List<SpellingError> errors =
                    service.check("PROVINCE_ENGLISH_NAME");

            assertTrue(errors.isEmpty());

            assertTrue(
                    requestBody.get().contains(
                            "text=province+english+name"));
        } finally {
            server.stop(0);
        }
    }
}
