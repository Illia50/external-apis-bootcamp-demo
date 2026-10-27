package com.accenture.externalapis.demo.client;

import com.accenture.externalapis.demo.dto.BookApiResponse;
import com.accenture.externalapis.demo.dto.BookDto;

final class BookMapper {

    private BookMapper() {
    }

    static BookDto toDto(BookApiResponse response) {
        if (response == null) {
            throw new ClientException("External service returned an empty book");
        }
        if (response.title() == null || response.price() == null) {
            throw new ClientException("External service returned a malformed book (id=" + response.id()
                    + "): missing title or price");
        }
        return new BookDto(response.title(), response.author(), response.genre(), response.price());
    }
}
