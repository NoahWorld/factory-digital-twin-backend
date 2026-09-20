package com.factorytwin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@Service
public class Auth {
  static final String COOKIE = "factory_twin_session";

  public record User(
      String id, String tenant, String email, String loginName, String displayName, String role) {
    public boolean admin() {
      return role.equals("platform_admin");
    }

    public ObjectNode present() {
      return Json.obj(
          "id",
          id,
          "email",
          email,
          "loginName",
          loginName,
          "displayName",
          displayName,
          "roles",
          List.of(role),
          "capabilities",
          Json.obj("canCreateProject", !role.equals("viewer"), "canManageUsers", admin()));
    }
  }

  final JdbcTemplate db;
  final PasswordEncoder passwords;
  final TransactionTemplate tx;
  final String bootstrapToken, defaultTenant;
  final boolean secureCookie;
  final String dummyPassword;

  public Auth(
      JdbcTemplate db,
      PasswordEncoder passwords,
      TransactionTemplate tx,
      @Value("${twin.bootstrap-token}") String token,
      @Value("${twin.default-tenant}") String tenant,
      @Value("${twin.secure-cookie}") boolean secure) {
    this.db = db;
    this.passwords = passwords;
    this.tx = tx;
    this.bootstrapToken = token;
    this.defaultTenant = tenant;
    this.secureCookie = secure;
    if (token.length() < 32)
      throw new IllegalStateException(
          "BOOTSTRAP_TOKEN must contain at least 32 random characters.");
    dummyPassword = passwords.encode(randomToken());
  }

  static String randomToken() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public User fromToken(String token) {
    if (token == null) throw new ApiException(401, "unauthenticated", "Please sign in.");
    var rows =
        db.query(
            "SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id AND u.tenant_id=s.tenant_id"
                + " WHERE s.token_hash=? AND s.expires_at>now() AND u.active",
            (r, i) ->
                new User(
                    r.getString("id"),
                    r.getString("tenant_id"),
                    r.getString("email"),
                    r.getString("login_name"),
                    r.getString("display_name"),
                    r.getString("role")),
            hash(token));
    if (rows.isEmpty())
      throw new ApiException(401, "unauthenticated", "Session expired or was revoked.");
    return rows.getFirst();
  }

  public String token(HttpServletRequest r) {
    if (r.getCookies() != null)
      for (Cookie c : r.getCookies()) if (c.getName().equals(COOKIE)) return c.getValue();
    return null;
  }

  public User require(HttpServletRequest r) {
    return fromToken(token(r));
  }

  public User admin(HttpServletRequest r) {
    User u = require(r);
    if (!u.admin())
      throw new ApiException(403, "forbidden", "Tenant administrator permission is required.");
    return u;
  }

  public void audit(User u, String project, String action, Object details) {
    db.update(
        "INSERT INTO audit_events(tenant_id,user_id,project_id,action,request_id,details)"
            + " VALUES(?,?,?,?,?,?::jsonb)",
        u.tenant(),
        u.id(),
        project,
        action,
        Optional.ofNullable(org.slf4j.MDC.get("requestId")).orElse("background"),
        Json.M.valueToTree(details).toString());
  }

  boolean setupRequired() {
    return db.queryForObject("SELECT count(*)=0 FROM users", Boolean.class);
  }

  User create(String tenant, JsonNode body, String forcedLogin, String forcedRole) {
    String email = Json.text(body, "email", 3, 254).toLowerCase(Locale.ROOT);
    Json.require(email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"), "Invalid email.");
    String login =
        forcedLogin == null
            ? Json.text(body, "loginName", 1, 80).toLowerCase(Locale.ROOT)
            : forcedLogin;
    Json.require(login.matches("[a-z][a-z0-9._-]{0,79}"), "Invalid loginName.");
    String display = Json.text(body, "displayName", 1, 120);
    JsonNode password = body.path("password");
    Json.require(
        password.isTextual()
            && password.textValue().length() >= 12
            && password.textValue().length() <= 256,
        "Password must contain 12–256 characters.");
    String role = forcedRole == null ? Json.text(body, "role", 1, 40) : forcedRole;
    Json.require(
        Set.of("platform_admin", "delivery_manager", "viewer").contains(role), "Invalid role.");
    User u = new User(Json.id(), tenant, email, login, display, role);
    db.update(
        "INSERT INTO users(id,tenant_id,email,login_name,display_name,password_hash,role)"
            + " VALUES(?,?,?,?,?,?,?)",
        u.id(),
        tenant,
        email,
        login,
        display,
        passwords.encode(password.textValue()),
        role);
    return u;
  }

  void issue(User u, HttpServletResponse s) {
    String token = randomToken();
    db.update(
        "INSERT INTO sessions(token_hash,tenant_id,user_id,expires_at) VALUES(?,?,?,now()+interval"
            + " '24 hours')",
        hash(token),
        u.tenant(),
        u.id());
    cookie(s, token, Duration.ofHours(24));
  }

  void cookie(HttpServletResponse s, String token, Duration age) {
    s.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(COOKIE, token)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(age)
            .build()
            .toString());
  }
}

@RestController
@RequestMapping("/api/v1")
class AuthController {
  final Auth a;

  AuthController(Auth a) {
    this.a = a;
  }

  @GetMapping("/auth/bootstrap-status")
  Object status() {
    return Json.obj("setupRequired", a.setupRequired());
  }

  @PostMapping("/auth/bootstrap")
  @ResponseStatus(HttpStatus.CREATED)
  Object bootstrap(
      @RequestBody JsonNode body,
      @RequestHeader(value = "X-Bootstrap-Token", defaultValue = "") String token,
      HttpServletResponse s) {
    if (!MessageDigest.isEqual(
        token.getBytes(StandardCharsets.UTF_8), a.bootstrapToken.getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(403, "invalid_bootstrap_token", "Invalid initialization token.");
    return a.tx.execute(
        st -> {
          a.db.execute("SELECT pg_advisory_xact_lock(746391)");
          if (!a.setupRequired())
            throw new ApiException(409, "already_initialized", "Installation already initialized.");
          a.db.update(
              "INSERT INTO tenants(id,name) VALUES(?,?)", a.defaultTenant, "Local installation");
          Auth.User u = a.create(a.defaultTenant, body, "admin", "platform_admin");
          a.issue(u, s);
          a.audit(u, null, "installation.bootstrap", Json.obj());
          return Json.obj("user", u.present());
        });
  }

  @PostMapping("/auth/login")
  Object login(@RequestBody JsonNode body, HttpServletRequest r, HttpServletResponse s) {
    String identifier =
        body.has("identifier")
            ? Json.text(body, "identifier", 1, 254)
            : Json.text(body, "email", 1, 254);
    identifier = identifier.toLowerCase(Locale.ROOT);
    String tenant = body.has("tenant") ? Json.text(body, "tenant", 1, 80) : a.defaultTenant;
    // Persistent, atomic attempt budget across API replicas. No forwarded IP headers are trusted.
    String key = Auth.hash(r.getRemoteAddr() + ":" + tenant + ":" + identifier);
    Integer tries =
        a.db.queryForObject(
            "INSERT INTO login_attempts(key_hash,attempts,resets_at) VALUES(?,1,now()+interval '15"
                + " minutes') ON CONFLICT(key_hash) DO UPDATE SET attempts=CASE WHEN"
                + " login_attempts.resets_at<now() THEN 1 ELSE login_attempts.attempts+1"
                + " END,resets_at=CASE WHEN login_attempts.resets_at<now() THEN now()+interval '15"
                + " minutes' ELSE login_attempts.resets_at END RETURNING attempts",
            Integer.class,
            key);
    if (tries > 20)
      throw new ApiException(
          429, "login_rate_limited", "Too many login attempts; retry after 15 minutes.");
    String password = body.path("password").asText("");
    Json.require(password.length() <= 256, "Password too long.");
    var rows =
        a.db.queryForList(
            "SELECT * FROM users WHERE tenant_id=? AND (login_name=? OR email=?) AND active",
            tenant,
            identifier,
            identifier);
    String hash = rows.isEmpty() ? a.dummyPassword : (String) rows.getFirst().get("password_hash");
    boolean valid = a.passwords.matches(password, hash);
    if (rows.isEmpty() || !valid)
      throw new ApiException(401, "invalid_credentials", "Invalid account or password.");
    var row = rows.getFirst();
    Auth.User u =
        new Auth.User(
            (String) row.get("id"),
            tenant,
            (String) row.get("email"),
            (String) row.get("login_name"),
            (String) row.get("display_name"),
            (String) row.get("role"));
    a.db.update("DELETE FROM login_attempts WHERE key_hash=?", key);
    a.issue(u, s);
    a.audit(u, null, "auth.login", Json.obj());
    return Json.obj("user", u.present());
  }

  @GetMapping("/auth/me")
  Object me(HttpServletRequest r) {
    return Json.obj("user", a.require(r).present());
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest r, HttpServletResponse s) {
    String token = a.token(r);
    if (token != null) a.db.update("DELETE FROM sessions WHERE token_hash=?", Auth.hash(token));
    a.cookie(s, "", Duration.ZERO);
  }

  @GetMapping("/users")
  Object users(HttpServletRequest r) {
    Auth.User u = a.admin(r);
    return Json.obj(
        "users",
        a.db.query(
            "SELECT * FROM users WHERE tenant_id=? ORDER BY created_at",
            (rs, i) ->
                Json.obj(
                    "id",
                    rs.getString("id"),
                    "loginName",
                    rs.getString("login_name"),
                    "email",
                    rs.getString("email"),
                    "displayName",
                    rs.getString("display_name"),
                    "role",
                    rs.getString("role"),
                    "active",
                    rs.getBoolean("active")),
            u.tenant()));
  }

  @PostMapping("/users")
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@RequestBody JsonNode b, HttpServletRequest r) {
    Auth.User u = a.admin(r);
    return a.tx.execute(
        st -> {
          Auth.User added = a.create(u.tenant(), b, null, null);
          a.audit(u, null, "user.create", Json.obj("userId", added.id()));
          return Json.obj("user", added.present());
        });
  }

  @PostMapping("/users/{id}/revoke-sessions")
  Object revoke(@PathVariable String id, HttpServletRequest r) {
    Auth.User u = a.admin(r);
    int n = a.db.update("DELETE FROM sessions WHERE tenant_id=? AND user_id=?", u.tenant(), id);
    a.audit(u, null, "user.revoke_sessions", Json.obj("userId", id));
    return Json.obj("revoked", n);
  }
}
