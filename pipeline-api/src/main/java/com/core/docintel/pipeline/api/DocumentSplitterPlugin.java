package com.core.docintel.pipeline.api;

import java.io.File;
import java.util.List;

/** A plugin that extracts text from a document and returns its text segments. */
public interface DocumentSplitterPlugin extends Plugin {

    /** Parse and split the given document, returning segments in pipeline order. */
    List<String> split(File document);

    @Override
    default Object execute(Object input) {
        if (!(input instanceof File file)) {
            throw new IllegalArgumentException("Document splitter expects a java.io.File input.");
        }
        return split(file);
    }
}
