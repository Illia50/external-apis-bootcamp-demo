package com.accenture.externalapis.demo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BookApiResponse(Long id,
                              String title,
                              String author,
                              String isbn,
                              String genre,
                              Double price,
                              Integer publishedYear) {
}
