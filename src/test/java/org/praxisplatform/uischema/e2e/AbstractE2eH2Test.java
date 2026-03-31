package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureApplication;
import org.praxisplatform.uischema.e2e.fixture.E2eFixtureDataSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = E2eFixtureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e-h2")
abstract class AbstractE2eH2Test {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected E2eFixtureDataSupport fixtureData;

    protected E2eFixtureDataSupport.SeedState state;

    @BeforeEach
    void resetFixture() {
        state = fixtureData.resetDefaultData();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected ResponseEntity<String> get(String path) {
        return rest.getRestTemplate().getForEntity(URI.create(url(path)), String.class);
    }

    protected ResponseEntity<String> delete(String path) {
        return rest.getRestTemplate().exchange(URI.create(url(path)), HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
    }

    protected ResponseEntity<String> deleteJson(String path, String body) {
        return rest.getRestTemplate().exchange(URI.create(url(path)), HttpMethod.DELETE, jsonEntity(body), String.class);
    }

    protected ResponseEntity<String> postJson(String path, String body) {
        return rest.getRestTemplate().postForEntity(URI.create(url(path)), jsonEntity(body), String.class);
    }

    protected ResponseEntity<String> putJson(String path, String body) {
        return rest.getRestTemplate().exchange(URI.create(url(path)), HttpMethod.PUT, jsonEntity(body), String.class);
    }

    protected ResponseEntity<String> exchange(String path, HttpMethod method, HttpHeaders headers) {
        return rest.getRestTemplate().exchange(URI.create(url(path)), method, new HttpEntity<>(headers), String.class);
    }

    protected JsonNode body(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody(), "Expected response body for " + response.getStatusCode());
        return objectMapper.readTree(response.getBody());
    }

    protected HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
