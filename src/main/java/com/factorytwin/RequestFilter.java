package com.factorytwin;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestFilter extends OncePerRequestFilter {
  private final Set<String> origins;
  private final String mode;
  private static final Logger log = LoggerFactory.getLogger(RequestFilter.class);

  public RequestFilter(
      @Value("${twin.allowed-origins}") String origins, @Value("${twin.mode}") String mode) {
    this.origins = Set.of(origins.split(","));
    this.mode = mode;
  }

  public boolean allows(String origin) {
    return origin != null && origins.contains(origin);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
      throws ServletException, IOException {
    String id = Json.id();
    r.setAttribute("requestId", id);
    s.setHeader("X-Request-Id", id);
    s.setHeader("X-Content-Type-Options", "nosniff");
    s.setHeader("Cache-Control", "no-store");
    MDC.put("requestId", id);
    long start = System.nanoTime();
    try {
      if (!mode.equals("api") && !r.getRequestURI().equals("/health"))
        throw new ApiException(
            404, "route_not_found", "This process only runs " + mode + " workloads.");
      boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod());
      // Browser writes must come from an explicitly configured origin. Non-browser clients send the
      // same Origin header.
      if (unsafe && !allows(r.getHeader("Origin")))
        throw new ApiException(
            403,
            "origin_denied",
            "A trusted Origin header is required for state-changing requests.");
      boolean binary = r.getRequestURI().matches(".*/(model|image|media)-assets");
      long limit = binary ? 100L * 1024 * 1024 : 2L * 1024 * 1024;
      if (r.getContentLengthLong() > limit)
        throw new ApiException(
            413, "request_too_large", "Request exceeds the endpoint body budget.");
      if (unsafe && r.getContentLengthLong() < 0 && r.getHeader("Transfer-Encoding") != null)
        throw new ApiException(411, "length_required", "Content-Length is required.");
      chain.doFilter(r, s);
    } catch (ApiException e) {
      if (s.isCommitted()) throw e;
      s.setStatus(e.status);
      s.setContentType("application/json");
      s.getWriter()
          .write(Json.obj("error", e.code, "message", e.getMessage(), "requestId", id).toString());
    } finally {
      log.info(
          "http method={} path={} status={} durationMs={}",
          r.getMethod(),
          r.getRequestURI(),
          s.getStatus(),
          (System.nanoTime() - start) / 1_000_000);
      MDC.remove("requestId");
    }
  }
}
