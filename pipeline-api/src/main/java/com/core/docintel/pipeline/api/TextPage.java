package com.core.docintel.pipeline.api;

public record TextPage(int pageNumber, String text) {
    public TextPage {
        if (pageNumber < 1) throw new IllegalArgumentException("Page number must be positive.");
        if (text == null) throw new IllegalArgumentException("Page text must not be null.");
    }
}
