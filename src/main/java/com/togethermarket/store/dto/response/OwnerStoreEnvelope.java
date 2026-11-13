package com.togethermarket.store.dto.response;

/** Local response until BE1 provides the shared wrapper; wire format already matches the API. */
public record OwnerStoreEnvelope<T>(T data) {}
