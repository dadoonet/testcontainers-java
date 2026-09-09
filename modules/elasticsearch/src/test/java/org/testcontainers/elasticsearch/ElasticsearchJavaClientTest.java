package org.testcontainers.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Usage examples for the Elasticsearch Java API Client and Kibana dashboard-as-code.
 * One Elasticsearch + Kibana pair is started for the class so these examples do not add
 * extra container boots on top of {@link ElasticsearchContainerTest} / {@link KibanaContainerTest}.
 */
class ElasticsearchJavaClientTest {

    private static final DockerImageName ELASTICSEARCH_IMAGE_LATEST =
        ElasticsearchContainerTest.ELASTICSEARCH_IMAGE_LATEST;

    private static final String ELASTICSEARCH_USERNAME = "elastic";

    private static final String BASIC_AUTH = Base64
        .getEncoder()
        .encodeToString(
            (ELASTICSEARCH_USERNAME + ":" + ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD).getBytes(
                    StandardCharsets.UTF_8
                )
        );

    private static Network network;

    private static ElasticsearchContainer elasticsearch;

    private static KibanaContainer kibana;

    @BeforeAll
    static void startElasticsearchAndKibana() {
        // elasticsearchAndKibana {
        network = Network.newNetwork();
        elasticsearch = new ElasticsearchContainer(ELASTICSEARCH_IMAGE_LATEST).withNetwork(network);
        kibana = new KibanaContainer(elasticsearch).withNetwork(network);
        elasticsearch.start();
        kibana.start();
        // }
    }

    @AfterAll
    static void stopElasticsearchAndKibana() {
        if (kibana != null) {
            kibana.close();
        }
        if (elasticsearch != null) {
            elasticsearch.close();
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void javaClientCanTalkToSecuredHttpsCluster() throws IOException {
        // elasticsearchJavaClient {
        try (
            ElasticsearchClient client = ElasticsearchClient.of(b -> {
                return b
                    .host("https://" + elasticsearch.getHttpHostAddress())
                    .sslContext(elasticsearch.createSslContextFromCa())
                    .usernameAndPassword(ELASTICSEARCH_USERNAME, ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD);
            })
        ) {
            HealthResponse health = client.cluster().health();
            InfoResponse info = client.info();
            // }}
            assertThat(health.status())
                .as("cluster health is at least yellow")
                .isIn(HealthStatus.Yellow, HealthStatus.Green);
            assertThat(info.version().number())
                .as("reported version matches the latest image")
                .isEqualTo(ElasticsearchContainerTest.ELASTICSEARCH_VERSION_LATEST);
            // elasticsearchJavaClient {{
        }
        // }
    }

    @Test
    void javaClientCanIndexAndGetADocument() throws IOException {
        try (
            ElasticsearchClient client = ElasticsearchClient.of(b -> {
                return b
                    .host("https://" + elasticsearch.getHttpHostAddress())
                    .sslContext(elasticsearch.createSslContextFromCa())
                    .usernameAndPassword(ELASTICSEARCH_USERNAME, ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD);
            })
        ) {
            client.index(ir -> ir.index("java-client-docs").id("1").document(Map.of("name", "Ada Lovelace")));

            assertThat(client.get(gr -> gr.index("java-client-docs").id("1"), Map.class).source())
                .as("indexed document can be retrieved")
                .containsEntry("name", "Ada Lovelace");
        }
    }

    @Test
    void canCreateDashboardAsCode() throws IOException, InterruptedException {
        // kibanaDashboardAsCode {
        try (
            ElasticsearchClient elasticsearchClient = ElasticsearchClient.of(b -> {
                return b
                    .host("https://" + elasticsearch.getHttpHostAddress())
                    .sslContext(elasticsearch.createSslContextFromCa())
                    .usernameAndPassword(ELASTICSEARCH_USERNAME, ElasticsearchContainer.ELASTICSEARCH_DEFAULT_PASSWORD);
            })
        ) {
            elasticsearchClient.index(ir -> ir.index("persons").id("1").document(Map.of("name", "Ada Lovelace")));
            elasticsearchClient.index(ir -> ir.index("persons").id("2").document(Map.of("name", "Alan Turing")));
            elasticsearchClient.indices().refresh(rr -> rr.index("persons"));

            HttpClient kibanaClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String kibanaUrl = "http://" + kibana.getHttpHostAddress();

            HttpResponse<String> dataViewResponse = kibana(
                kibanaClient,
                kibanaUrl,
                "POST",
                "/api/data_views/data_view",
                """
                {
                  "data_view": {
                    "id": "persons",
                    "title": "persons",
                    "name": "Persons"
                  }
                }
                """
            );
            // }}
            assertThat(dataViewResponse.statusCode()).as(dataViewResponse.body()).isEqualTo(200);
            // kibanaDashboardAsCode {{

            HttpResponse<String> dashboardResponse = kibana(
                kibanaClient,
                kibanaUrl,
                "PUT",
                "/api/dashboards/persons-dashboard",
                """
                {
                  "title": "Persons dashboard",
                  "panels": [
                    {
                      "type": "markdown",
                      "grid": { "x": 0, "y": 0, "w": 24, "h": 8 },
                      "config": { "content": "# Persons\\n\\nIndexed from the Elasticsearch Java client." }
                    },
                    {
                      "type": "vis",
                      "grid": { "x": 24, "y": 0, "w": 24, "h": 8 },
                      "config": {
                        "type": "metric",
                        "title": "Person count",
                        "data_source": {
                          "type": "esql",
                          "query": "FROM persons | STATS count = COUNT()"
                        },
                        "metrics": [{ "type": "primary", "column": "count" }]
                      }
                    }
                  ]
                }
                """
            );
            // }}
            assertThat(dashboardResponse.statusCode()).as(dashboardResponse.body()).isIn(200, 201);
            assertThat(dashboardResponse.body()).contains("Persons dashboard");
            // kibanaDashboardAsCode {{
        }
        // }
    }

    // kibanaHttpHelper {
    private static HttpResponse<String> kibana(
        HttpClient kibanaClient,
        String kibanaUrl,
        String method,
        String path,
        String json
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
            .newBuilder()
            .uri(URI.create(kibanaUrl + path))
            .header("Authorization", "Basic " + BASIC_AUTH)
            .header("kbn-xsrf", "true")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json))
            .build();
        return kibanaClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
    // }
}
