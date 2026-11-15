package com.togethermarket.product.image;

import com.togethermarket.global.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

final class ProductImageValidator {
  static final int MAX_BYTES = 5 * 1024 * 1024;

  record Image(String contentType, String extension, byte[] bytes) {}

  private ProductImageValidator() {}

  static Image validate(MultipartFile file) {
    if (file.isEmpty() || file.getSize() > MAX_BYTES)
      throw invalid("1바이트 이상 5MB 이하의 이미지 파일을 선택해 주세요.");
    try {
      byte[] bytes = file.getBytes();
      if (bytes.length == 0 || bytes.length > MAX_BYTES)
        throw invalid("1바이트 이상 5MB 이하의 이미지 파일을 선택해 주세요.");
      String type;
      String extension;
      if (bytes.length >= 3
          && (bytes[0] & 0xff) == 0xff
          && (bytes[1] & 0xff) == 0xd8
          && (bytes[2] & 0xff) == 0xff) {
        type = "image/jpeg";
        extension = ".jpg";
      } else if (bytes.length >= 8
          && (bytes[0] & 0xff) == 0x89
          && bytes[1] == 'P'
          && bytes[2] == 'N'
          && bytes[3] == 'G'
          && bytes[4] == 13
          && bytes[5] == 10
          && bytes[6] == 26
          && bytes[7] == 10) {
        type = "image/png";
        extension = ".png";
      } else {
        throw invalid("JPEG 또는 PNG 이미지만 업로드할 수 있습니다.");
      }
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw invalid("이미지 파일을 읽을 수 없습니다.");
        var reader = readers.next();
        try {
          reader.setInput(input, true, true);
          int width = reader.getWidth(0);
          int height = reader.getHeight(0);
          if (width < 1 || height < 1 || width > 4096 || height > 4096)
            throw invalid("이미지 크기는 최대 4096×4096 픽셀입니다.");
        } finally {
          reader.dispose();
        }
      }
      return new Image(type, extension, bytes);
    } catch (IOException | IndexOutOfBoundsException ex) {
      throw invalid("이미지 파일을 읽을 수 없습니다.");
    }
  }

  private static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", message);
  }
}
