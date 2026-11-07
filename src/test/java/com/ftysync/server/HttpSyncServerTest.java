package com.ftysync.server;

import com.ftysync.core.Blacklist;
import com.ftysync.core.Hash;
import com.ftysync.core.PackManifest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSyncServerTest {

    @TempDir
    Path gameDir;

    private HttpSyncServer server;
    private HttpClient client;
    private String base;
    private PackPublisher publisher;
    private FilePackRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        Path storeDir = gameDir.resolve("ftyupater");
        ContentScanner scanner = new ContentScanner(gameDir, Blacklist.empty(), Set.of("own.jar"));
        ContentStore store = new ContentStore(storeDir);
        GenerationStore generations = new GenerationStore(storeDir);
        GenerationJournal journal = new GenerationJournal(storeDir.resolve("journal.jsonl"));
        publisher = new PackPublisher(scanner, store, generations, journal,
                "fty", "1.20.1", "forge-47.4.0", 2);
        repository = new FilePackRepository(store, generations, journal, 2);
        server = new HttpSyncServer(0, "127.0.0.1", repository, "2.0.0");
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = gameDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void healthReturnsOkAndCurrentGeneration() throws Exception {
        write("mods/a.jar", "AAA");
        String generation = publisher.publish().manifest().generation();

        HttpResponse<String> response = get("/api/v1/health");

        assertEquals(200, response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(json.get("ok").getAsBoolean());
        assertEquals("ftyupater", json.get("mod").getAsString());
        assertEquals(2, json.get("schema").getAsInt());
        assertEquals("2.0.0", json.get("modVersion").getAsString());
        assertEquals(generation, json.get("generation").getAsString());
    }

    @Test
    void packReturnsParseableCurrentManifest() throws Exception {
        write("mods/a.jar", "AAA");
        write("clientpack/config/x.toml", "X");
        publisher.publish();

        HttpResponse<String> response = get("/api/v1/pack");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        PackManifest manifest = PackManifest.parse(response.body());
        assertEquals(2, manifest.size());
    }

    @Test
    void packByGenerationReturnsRetainedHistory() throws Exception {
        write("mods/a.jar", "AAA");
        String generation1 = publisher.publish().manifest().generation();
        write("mods/a.jar", "BBB");
        publisher.publish();

        HttpResponse<String> response = get("/api/v1/pack?generation=" + generation1);

        assertEquals(200, response.statusCode());
        assertEquals(generation1, PackManifest.parse(response.body()).generation());
    }

    @Test
    void unknownGenerationIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/pack?generation=does-not-exist");

        assertEquals(404, response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertFalse(json.get("ok").getAsBoolean());
        assertEquals("not_found", json.get("error").getAsString());
    }

    @Test
    void generationsListsRetainedOnesNewestFirst() throws Exception {
        write("mods/a.jar", "AAA");
        String generation1 = publisher.publish().manifest().generation();
        write("mods/a.jar", "BBB");
        String generation2 = publisher.publish().manifest().generation();

        HttpResponse<String> response = get("/api/v1/generations");

        assertEquals(200, response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(2, json.get("schema").getAsInt());
        var list = json.getAsJsonArray("generations");
        assertEquals(2, list.size());
        assertEquals(generation2, list.get(0).getAsJsonObject().get("generation").getAsString());
        assertEquals(generation1, list.get(1).getAsJsonObject().get("generation").getAsString());
    }

    @Test
    void fileDownloadReturnsExactBytes() throws Exception {
        write("mods/a.jar", "AAA");
        publisher.publish();
        String sha = Hash.sha256("AAA".getBytes(StandardCharsets.UTF_8));

        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/files/" + sha)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), response.body());
        assertEquals("3", response.headers().firstValue("content-length").orElse(""));
        assertEquals("\"" + sha + "\"", response.headers().firstValue("etag").orElse(""));
    }

    @Test
    void invalidShaIsBadRequest() throws Exception {
        HttpResponse<String> response = get("/api/v1/files/not-a-hash");

        assertEquals(400, response.statusCode());
        assertEquals("bad_request",
                JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        assertEquals(404, get("/api/v1/nope").statusCode());
    }

    @Test
    void nonGetMethodIsRejected() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/pack"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }
}
