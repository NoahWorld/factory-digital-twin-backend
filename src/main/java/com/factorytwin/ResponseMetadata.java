package com.factorytwin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ResponseMetadata implements ResponseBodyAdvice<Object> {
  public boolean supports(MethodParameter p, Class<? extends HttpMessageConverter<?>> c) {
    return true;
  }

  public Object beforeBodyWrite(
      Object body,
      MethodParameter p,
      MediaType media,
      Class<? extends HttpMessageConverter<?>> c,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    String id = response.getHeaders().getFirst("X-Request-Id");
    if (body instanceof ObjectNode node) {
      node.put("requestId", id);
      return node;
    }
    if (body instanceof Map<?, ?> map) {
      ObjectNode node = Json.M.valueToTree(map);
      node.put("requestId", id);
      return node;
    }
    return body;
  }
}
