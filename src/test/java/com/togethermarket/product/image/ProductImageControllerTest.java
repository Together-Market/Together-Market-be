package com.togethermarket.product.image;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProductImageControllerTest {
  private final OwnerAccess access = mock(OwnerAccess.class);
  private final StoreRepository stores = mock(StoreRepository.class);
  private final ProductImageStore images = mock(ProductImageStore.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final ProductImageController controller =
      new ProductImageController(access, stores, images, "test-bucket", "https://api.example.com");

  @Test
  void uploadedImageReturnsUrlThatCanBeStoredInProduct() throws Exception {
    when(access.requireOwner(request)).thenReturn(9L);
    when(stores.findByIdAndOwnerId(3L, 9L))
        .thenReturn(Optional.of(new Store(9L, "가게", "카페", "용인", null, null, "010")));
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
    var file = new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray());

    var result = controller.upload(request, "3", file);

    assertTrue(
        result
            .data()
            .imageUrl()
            .matches("https://api\\.example\\.com/product-images/[0-9a-f-]+\\.png"));
    verify(images)
        .put(
            eq("test-bucket"),
            matches("[0-9a-f-]+\\.png"),
            eq("image/png"),
            eq(bytes.toByteArray()));
  }

  @Test
  void otherOwnersStoreCannotUpload() {
    when(access.requireOwner(request)).thenReturn(9L);
    var file = new MockMultipartFile("file", "x.png", "image/png", new byte[] {1});

    ApiException error =
        assertThrows(ApiException.class, () -> controller.upload(request, "3", file));

    assertEquals("NOT_FOUND", error.code());
    verifyNoInteractions(images);
  }

  @Test
  void rejectsNonImageAndOversizedFileWithoutStorageCall() {
    when(access.requireOwner(request)).thenReturn(9L);
    when(stores.findByIdAndOwnerId(3L, 9L))
        .thenReturn(Optional.of(new Store(9L, "가게", "카페", "용인", null, null, "010")));
    var text = new MockMultipartFile("file", "x.jpg", "image/jpeg", "hello".getBytes());
    var large = new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]);

    assertEquals(
        "INVALID_IMAGE",
        assertThrows(ApiException.class, () -> controller.upload(request, "3", text)).code());
    assertEquals(
        "INVALID_IMAGE",
        assertThrows(ApiException.class, () -> controller.upload(request, "3", large)).code());
    verifyNoInteractions(images);
  }
}
