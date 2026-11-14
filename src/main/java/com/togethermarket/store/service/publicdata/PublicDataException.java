package com.togethermarket.store.service.publicdata;

/** Messages deliberately omit request URLs, credentials and provider response bodies. */
public class PublicDataException extends RuntimeException {
  public PublicDataException(String message) {
    super(message);
  }
}
