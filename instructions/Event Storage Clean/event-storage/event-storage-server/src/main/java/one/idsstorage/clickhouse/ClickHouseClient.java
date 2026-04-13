package one.idsstorage.clickhouse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class ClickHouseClient {
    private final HttpClient httpClient;
    private final String clickhouseUrl;

    public ClickHouseClient(@Value("${app.clickhouse.url:http://localhost:8123}") String clickhouseUrl) {
        this.clickhouseUrl = clickhouseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void insertJsonEachRow(String insertSqlPrefix, String jsonRows) throws IOException, InterruptedException {
        String body = insertSqlPrefix + "\n" + jsonRows;
        HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("ClickHouse insert failed: " + response.statusCode() + " body=" + response.body());
        }
    }

    public String query(String sql) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(clickhouseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("ClickHouse query failed: " + response.statusCode() + " body=" + response.body());
        }
        return response.body();
    }
}
