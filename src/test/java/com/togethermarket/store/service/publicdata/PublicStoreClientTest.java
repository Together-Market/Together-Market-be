package com.togethermarket.store.service.publicdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublicStoreClientTest {
  @Test
  @SuppressWarnings("unchecked")
  void keyEncodedOnce() throws Exception {
    assertKeyEncodedOnce("abc+123/xyz==");
    assertKeyEncodedOnce("abc%2B123%2Fxyz%3D%3D");
  }

  @SuppressWarnings("unchecked")
  private void assertKeyEncodedOnce(String key) throws Exception {
    var http = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(PublicStoreParserTest.YPAY);
    doReturn(response).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    new PublicStoreClient(new PublicStoreParser(), http).fetch("YPAY", key, 5, 3, null);
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertTrue(request.getValue().uri().getRawQuery().contains("abc%2B123%2Fxyz%3D%3D"));
    assertFalse(request.getValue().uri().getRawQuery().contains("%252B"));
  }

  @Test
  void missingKeyDoesNotCallProvider() {
    var http = mock(HttpClient.class);
    assertThrows(
        PublicDataException.class,
        () -> new PublicStoreClient(new PublicStoreParser(), http).fetch("YPAY", null, 1, 5, null));
    verifyNoInteractions(http);
  }
}
