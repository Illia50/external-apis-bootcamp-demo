package com.accenture.externalapis.demo.client;

import com.accenture.externalapis.demo.config.ExternalServiceProperties;
import com.accenture.externalapis.demo.dto.BookApiResponse;
import com.accenture.externalapis.demo.dto.BookDto;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

@Component
public class BookWebClientImpl implements BookWebClient {

    private static final Logger log = LoggerFactory.getLogger(BookWebClientImpl.class);

    private static final String BOOKS_PATH = "/books";
    private static final String BOOK_BY_ID_PATH = "/books/{id}";

    private final WebClient webClient;
    private final Duration readTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;

    public BookWebClientImpl(WebClient.Builder builder, ExternalServiceProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
                .responseTimeout(properties.readTimeout());

        this.webClient = builder
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.readTimeout = properties.readTimeout();
        this.maxRetries = properties.maxRetries();
        this.retryBackoff = properties.retryBackoff();
    }

    @Override
    public Mono<BookDto> getBookAsync(Long id) {
        String target = "book " + id;
        return webClient.get()
                .uri(BOOK_BY_ID_PATH, id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> statusToException(response, target))
                .bodyToMono(BookApiResponse.class)
                .timeout(readTimeout)
                .retryWhen(retrySpec(target))
                .switchIfEmpty(Mono.error(() -> new ClientException(
                        "External service returned an empty response for " + target)))
                .map(BookMapper::toDto)
                .onErrorMap(ex -> !(ex instanceof ClientException), ex -> mapError(ex, target))
                .doOnError(ex -> log.warn(ex.getMessage()));
    }

    @Override
    public Flux<BookDto> getAllBooksAsync() {
        String target = "all books";
        return webClient.get()
                .uri(BOOKS_PATH)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> statusToException(response, target))
                .bodyToFlux(BookApiResponse.class)
                .timeout(readTimeout)
                .retryWhen(retrySpec(target))
                .map(BookMapper::toDto)
                .onErrorMap(ex -> !(ex instanceof ClientException), ex -> mapError(ex, target))
                .doOnError(ex -> log.warn(ex.getMessage()));
    }

    @Override
    public Mono<List<BookDto>> getBooksInParallel(Long id1, Long id2) {
        return Mono.zip(getBookAsync(id1), getBookAsync(id2))
                .map(books -> List.of(books.getT1(), books.getT2()));
    }

    private Mono<ClientException> statusToException(ClientResponse response, String target) {
        return response.createException()
                .map(ex -> {
                    int status = ex.getStatusCode().value();
                    String kind = status >= 500 ? "failed while fetching " : "rejected the request for ";
                    return new ClientException("External service " + kind + target
                            + " with status " + status, status, ex);
                });
    }

    private ClientException mapError(Throwable ex, String target) {
        if (ex instanceof WebClientResponseException responseEx
                && responseEx.getStatusCode().is2xxSuccessful()) {
            return new ClientException("Could not read the external service response for " + target
                    + ": " + ex.getMessage(), ex);
        }
        if (ex instanceof WebClientResponseException responseEx) {
            int status = responseEx.getStatusCode().value();
            return new ClientException("External service returned status " + status + " for " + target,
                    status, ex);
        }
        if (ex instanceof WebClientRequestException) {
            return new ClientException("External service is unreachable or timed out while fetching "
                    + target + ": " + ex.getMessage(), ex);
        }
        if (ex instanceof TimeoutException) {
            return new ClientException("External service did not respond within " + readTimeout
                    + " while fetching " + target, ex);
        }
        if (ex instanceof CodecException || ex instanceof UnsupportedMediaTypeException) {
            return new ClientException("Could not read the external service response for " + target
                    + ": " + ex.getMessage(), ex);
        }
        return new ClientException("Unexpected error while fetching " + target + ": " + ex.getMessage(), ex);
    }

    private Retry retrySpec(String target) {
        return Retry.backoff(maxRetries, retryBackoff)
                .filter(BookWebClientImpl::isTransient)
                .doBeforeRetry(signal -> log.info("Retrying {} (attempt {}) after: {}",
                        target, signal.totalRetries() + 1, signal.failure().getMessage()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean isTransient(Throwable ex) {
        return (ex instanceof ClientException clientEx && clientEx.isServerError())
                || ex instanceof WebClientRequestException
                || ex instanceof TimeoutException;
    }
}
