package com.togethermarket.store.service.geocoding;

/** Live preview only. No provider response may be persisted through this port. */
public interface AddressGeocoder {
  Coordinates lookup(String address);
}
