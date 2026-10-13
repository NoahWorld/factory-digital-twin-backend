package com.factorytwin;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class Errors {
  private static final Logger log = LoggerFactory.getLogger(Errors.class);

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> uploadSize(Exception e, HttpServletRequest r) {
    return error(413, "engineering_source_size", "IFC/DXF source must be at most 100 MiB.", r);
  }

  @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
  ResponseEntity<?> multipartInput(Exception e, HttpServletRequest r) {
    return error(400, "missing_request_field", "Required request parameter or file is missing.", r);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> app(ApiException e, HttpServletRequest r) {
    return error(e.status, e.code, e.getMessage(), r);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<?> input(Exception e, HttpServletRequest r) {
    return error(400, "invalid_json", "Malformed JSON request.", r);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e, HttpServletRequest r) {
    return error(404, "route_not_found", "Endpoint not found.", r);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<?> mediaType(Exception e, HttpServletRequest r) {
    return error(415, "unsupported_media_type", "Use the endpoint's supported Content-Type.", r);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> constraint(Exception e, HttpServletRequest r) {
    log.warn("Database constraint rejected {}", r.getRequestURI(), e);
    return error(
        409,
        "constraint_conflict",
        "A unique value or referenced record conflicts with this operation.",
        r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest r) {
    log.error("Request failed {} {}", r.getMethod(), r.getRequestURI(), e);
    return error(
        500,
        "internal_error",
        "Operation failed. Use requestId to find the server diagnostics.",
        r);
  }

  private ResponseEntity<?> error(int status, String code, String message, HttpServletRequest r) {
    return ResponseEntity.status(status)
        .body(
            Json.obj("error", code, "message", message, "requestId", r.getAttribute("requestId")));
  }
}
