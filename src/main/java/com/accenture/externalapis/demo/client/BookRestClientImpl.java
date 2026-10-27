package com.accenture.externalapis.demo.client;

import com.accenture.externalapis.demo.config.ExternalServiceProperties;
import com.accenture.externalapis.demo.dto.BookApiResponse;
import com.accenture.externalapis.demo.dto.BookDto;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class BookRestClientImpl implements BookRestClient {

    private static final Logger log = LoggerFactory.getLogger(BookRestClientImpl.class);

    private static final String BOOKS_PATH = "/books";
    private static final String BOOK_BY_ID_PATH = "/books/{id}";

    private final RestClient restClient;

    public BookRestClientImpl(RestClient.Builder builder, ExternalServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public BookDto getBook(Long id) {
        String target = "book " + id;
        try {
            BookApiResponse response = restClient.get()
                    .uri(BOOK_BY_ID_PATH, id)
                    .retrieve()
                    .body(BookApiResponse.class);
            return BookMapper.toDto(response);
        } catch (RestClientException ex) {
            throw toClientException(ex, target);
        }
    }

    @Override
    public List<BookDto> getAllBooks() {
        try {
            BookApiResponse[] response = restClient.get()
                    .uri(BOOKS_PATH)
                    .retrieve()
                    .body(BookApiResponse[].class);
            if (response == null) {
                return List.of();
            }
            return Arrays.stream(response)
                    .map(BookMapper::toDto)
                    .toList();
        } catch (RestClientException ex) {
            throw toClientException(ex, "all books");
        }
    }

    private ClientException toClientException(RestClientException ex, String target) {
        ClientException result;
        if (ex instanceof HttpClientErrorException clientError) {

            int status = clientError.getStatusCode().value();
            result = new ClientException("External service rejected the request for " + target
                    + " with status " + status, status, ex);
        } else if (ex instanceof HttpServerErrorException serverError) {

            int status = serverError.getStatusCode().value();
            result = new ClientException("External service failed while fetching " + target
                    + " with status " + status, status, ex);
        } else if (ex instanceof RestClientResponseException otherStatus) {

            int status = otherStatus.getStatusCode().value();
            result = new ClientException("External service returned unexpected status " + status
                    + " for " + target, status, ex);
        } else if (ex instanceof ResourceAccessException) {
            result = new ClientException("External service is unreachable or timed out while fetching "
                    + target + ": " + ex.getMessage(), ex);
        } else {
            result = new ClientException("Could not read the external service response for " + target
                    + ": " + ex.getMessage(), ex);
        }
        log.warn(result.getMessage());
        return result;
    }
}
