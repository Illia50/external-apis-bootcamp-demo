package com.accenture.externalapis.demo.client;

import com.accenture.externalapis.demo.config.ExternalServiceProperties;
import com.accenture.externalapis.demo.dto.BookDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@SpringBootTest
@AutoConfigureMockMvc
class BookClientsIntegrationTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(10);

    private static final long NOT_FOUND = 404;
    private static final long TEAPOT = 418;
    private static final long SERVER_ERROR = 500;
    private static final long SLOW = 777;
    private static final long MALFORMED_JSON = 888;
    private static final long EMPTY_BODY = 889;
    private static final long MISSING_FIELDS = 890;
    private static final long HTML_BODY = 891;

    private static final AtomicInteger serverErrorHits = new AtomicInteger();
    private static final HttpServer server = startFakeService();

    @Autowired
    private BookRestClient restClient;

    @Autowired
    private BookWebClient webClient;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void externalServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("external-service.base-url",
                () -> "http://localhost:" + server.getAddress().getPort() + "/api");
        registry.add("external-service.read-timeout", () -> "500ms");
        registry.add("external-service.max-retries", () -> "1");
        registry.add("external-service.retry-backoff", () -> "10ms");
    }

    @AfterAll
    static void stopFakeService() {
        server.stop(0);
    }

    // RestClient

    @Test
    void restClient_fetchesOneBook() {
        BookDto book = restClient.getBook(1L);

        assertEquals(new BookDto("Dune", "Frank Herbert", "Science Fiction", 9.99), book);
    }

    @Test
    void restClient_fetchesAllBooks() {
        List<BookDto> books = restClient.getAllBooks();

        assertThat(books).extracting(BookDto::title).containsExactly("Dune", "Emma");
    }

    @Test
    void restClient_wraps4xx() {
        ClientException ex = assertThrows(ClientException.class, () -> restClient.getBook(NOT_FOUND));
        assertEquals(404, ex.getStatusCode());

        ClientException teapot = assertThrows(ClientException.class, () -> restClient.getBook(TEAPOT));
        assertEquals(418, teapot.getStatusCode());
    }

    @Test
    void restClient_wraps5xx() {
        ClientException ex = assertThrows(ClientException.class, () -> restClient.getBook(SERVER_ERROR));

        assertEquals(500, ex.getStatusCode());
        assertThat(ex.isServerError()).isTrue();
    }

    @Test
    void restClient_wrapsTimeout() {
        ClientException ex = assertThrows(ClientException.class, () -> restClient.getBook(SLOW));

        assertFalse(ex.hasStatusCode());
        assertThat(ex.getCause()).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }

    @Test
    void restClient_wrapsBadBodies() {
        assertThrows(ClientException.class, () -> restClient.getBook(MALFORMED_JSON));
        assertThrows(ClientException.class, () -> restClient.getBook(EMPTY_BODY));
        assertThrows(ClientException.class, () -> restClient.getBook(MISSING_FIELDS));
        assertThrows(ClientException.class, () -> restClient.getBook(HTML_BODY));
    }

    @Test
    void restClient_wrapsConnectionRefused() {
        BookRestClient unreachable = new BookRestClientImpl(RestClient.builder(), unreachableProperties());

        ClientException ex = assertThrows(ClientException.class, () -> unreachable.getBook(1L));
        assertNull(ex.getStatusCode());
    }

    // WebClient

    @Test
    void webClient_fetchesOneBook() {
        StepVerifier.create(webClient.getBookAsync(1L))
                .expectNext(new BookDto("Dune", "Frank Herbert", "Science Fiction", 9.99))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void webClient_fetchesAllBooks() {
        StepVerifier.create(webClient.getAllBooksAsync())
                .expectNextMatches(book -> book.title().equals("Dune"))
                .expectNextMatches(book -> book.title().equals("Emma"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void webClient_fetchesTwoBooksInParallel() {
        StepVerifier.create(webClient.getBooksInParallel(1L, 2L))
                .assertNext(books -> assertThat(books).extracting(BookDto::title).containsExactly("Dune", "Emma"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void webClient_wraps4xxWithoutRetrying() {
        expectClientError(webClient.getBookAsync(NOT_FOUND), 404);
        expectClientError(webClient.getBookAsync(TEAPOT), 418);
    }

    @Test
    void webClient_retries5xxThenWraps() {
        serverErrorHits.set(0);

        expectClientError(webClient.getBookAsync(SERVER_ERROR), 500);

        assertEquals(2, serverErrorHits.get(), "1 call + 1 retry (max-retries=1)");
    }

    @Test
    void webClient_wrapsTimeout() {
        expectClientError(webClient.getBookAsync(SLOW), null);
    }

    @Test
    void webClient_wrapsBadBodies() {
        expectClientError(webClient.getBookAsync(MALFORMED_JSON), null);
        expectClientError(webClient.getBookAsync(EMPTY_BODY), null);
        expectClientError(webClient.getBookAsync(MISSING_FIELDS), null);
        expectClientError(webClient.getBookAsync(HTML_BODY), null);
    }

    @Test
    void webClient_parallelFailsIfOneBookFails() {
        expectClientError(webClient.getBooksInParallel(1L, NOT_FOUND), 404);
    }

    @Test
    void webClient_wrapsConnectionRefused() {
        BookWebClient unreachable = new BookWebClientImpl(WebClient.builder(), unreachableProperties());

        expectClientError(unreachable.getBookAsync(1L), null);
    }

    // HTTP endpoints

    @Test
    void endpoint_returns502ForBlockingClientError() throws Exception {
        mockMvc.perform(get("/api/books/{id}", NOT_FOUND))
                .andExpect(status().isBadGateway());
    }

    @Test
    void endpoint_returns502ForReactiveClientError() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/books/async/{id}", NOT_FOUND))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isBadGateway());
    }

    @Test
    void endpoint_returnsTwoBooksInParallel() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/books/async/parallel").param("id1", "1").param("id2", "2"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Dune"));
    }

    // helpers

    private static void expectClientError(org.reactivestreams.Publisher<?> publisher, Integer expectedStatus) {
        StepVerifier.create(publisher)
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(ClientException.class);
                    assertEquals(expectedStatus, ((ClientException) ex).getStatusCode());
                })
                .verify(VERIFY_TIMEOUT);
    }

    private static ExternalServiceProperties unreachableProperties() {
        // Port 1 is never open, so the connection is refused
        return new ExternalServiceProperties("http://localhost:1/api",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, Duration.ofMillis(10));
    }

    private static HttpServer startFakeService() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fake-book-service");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.createContext("/api/books", BookClientsIntegrationTest::handle);
            httpServer.start();
            return httpServer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String book1 = """
                {"id":1,"title":"Dune","author":"Frank Herbert","isbn":"9780441013593",\
                "genre":"Science Fiction","price":9.99,"publishedYear":1965}""";
        String book2 = """
                {"id":2,"title":"Emma","author":"Jane Austen","isbn":"9780141439587",\
                "genre":"Classic","price":7.5,"publishedYear":1815}""";

        switch (path) {
            case "/api/books" -> send(exchange, 200, "application/json", "[" + book1 + "," + book2 + "]");
            case "/api/books/1" -> send(exchange, 200, "application/json", book1);
            case "/api/books/2" -> send(exchange, 200, "application/json", book2);
            case "/api/books/418" -> send(exchange, 418, "application/json", "{\"error\":\"I'm a teapot\"}");
            case "/api/books/500" -> {
                serverErrorHits.incrementAndGet();
                send(exchange, 500, "application/json", "{\"error\":\"boom\"}");
            }
            case "/api/books/777" -> {
                sleep(2_000);
                send(exchange, 200, "application/json", book1);
            }
            case "/api/books/888" -> send(exchange, 200, "application/json", "{ this is not json");
            case "/api/books/889" -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
            case "/api/books/890" -> send(exchange, 200, "application/json", "{\"id\":890,\"author\":\"Nobody\"}");
            case "/api/books/891" -> send(exchange, 200, "text/html", "<html><body>Oops</body></html>");
            default -> send(exchange, 404, "application/json", "{\"error\":\"Book not found\"}");
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
