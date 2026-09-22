package com.nextgem.smartrag.vectorstore;

import java.util.Map;

/**
 * Representation of an individual document vector record for ChromaDB / Vector Store.
 */
public record VectorDocument(
        String id,
        String documentName,
        int pageNumber,
        String heading,
        String text,
        float[] embedding,
        Map<String, Object> metadata
) {}
