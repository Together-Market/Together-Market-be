package com.togethermarket.global.api;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  private final tools.jackson.databind.ObjectMapper mapper;

  public ApiExceptionHandler(tools.jackson.databind.ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(ApiError.of(e.code(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                "VALIDATION_ERROR",
                "입력값을 확인해 주세요.",
                e.getBindingResult().getFieldErrors().stream()
                    .map(
                        f ->
                            new ApiError.FieldError(
                                jsonField(e.getBindingResult().getTarget(), f.getField()),
                                f.getDefaultMessage()))
                    .toList()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> malformed(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest()
        .body(ApiError.of("VALIDATION_ERROR", "JSON 객체 형식으로 입력해 주세요."));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> unsupportedMethod(
      org.springframework.web.HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(e.getStatusCode())
        .headers(e.getHeaders())
        .body(ApiError.of("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> unsupportedMedia(
      org.springframework.web.HttpMediaTypeNotSupportedException e) {
    return ResponseEntity.status(e.getStatusCode())
        .headers(e.getHeaders())
        .body(ApiError.of("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다."));
  }

  // Resolve each segment through the same Jackson metadata used to read the request.
  // Preserve collection indexes and honor explicit JsonProperty/JsonNaming annotations.
  private String jsonField(Object target, String path) {
    if (target == null) return path;
    var type = mapper.constructType(target.getClass());
    var config = mapper.deserializationConfig();
    var introspector = config.classIntrospectorInstance().forOperation(config);
    var result = new java.util.ArrayList<String>();
    for (String segment : path.split("\\.")) {
      int bracket = segment.indexOf('[');
      String name = bracket < 0 ? segment : segment.substring(0, bracket);
      String suffix = bracket < 0 ? "" : segment.substring(bracket);
      var property =
          type == null
              ? null
              : introspector
                  .introspectForDeserialization(type, introspector.introspectClassAnnotations(type))
                  .findProperties()
                  .stream()
                  .filter(p -> p.getInternalName().equals(name))
                  .findFirst()
                  .orElse(null);
      result.add((property == null ? name : property.getName()) + suffix);
      type = property == null ? null : property.getPrimaryType();
      for (int index = 0; index < suffix.length(); index++)
        if (suffix.charAt(index) == '[' && type != null) type = type.getContentType();
    }
    return String.join(".", result);
  }
}
