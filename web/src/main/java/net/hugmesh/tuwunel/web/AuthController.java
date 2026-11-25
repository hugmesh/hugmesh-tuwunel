package net.hugmesh.tuwunel.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Sign-in with a Matrix username and password, and the session the UI reads. The password is used once, to log in. */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final int MAX_FIELD = 512;

    private final WebProperties props;
    private final SessionStore sessions;
    private final HomeserverClient homeserver;
    private final LoginLimiter limiter;

    public AuthController(WebProperties props, SessionStore sessions, HomeserverClient homeserver, LoginLimiter limiter) {
        this.props = props;
        this.sessions = sessions;
        this.homeserver = homeserver;
        this.limiter = limiter;
    }

    /** Body: {"username": "...", "password": "..."}. 204 + the session cookie; 401 refused; 429 slow down; 502 homeserver down. */
    @PostMapping(value = "/bff/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> login(@RequestBody Map<String, String> body, ServerWebExchange ex) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || username.isBlank() || password.isEmpty()
                || username.length() > MAX_FIELD || password.length() > MAX_FIELD) {
            return Mono.just(ResponseEntity.badRequest().<Void>build());
        }
        var remote = ex.getRequest().getRemoteAddress();
        String address = remote == null || remote.getAddress() == null ? "?" : remote.getAddress().getHostAddress();
        if (!limiter.allow(address, username)) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "60").<Void>build());
        }
        return homeserver.login(username.trim(), password)
                .flatMap(l -> homeserver.displayName(l.userId()).map(name -> {
                    var s = sessions.create(l.token(), l.userId(), name);
                    return ResponseEntity.noContent()
                            .header(HttpHeaders.SET_COOKIE, cookie(SessionStore.COOKIE, s.id(), props.sessionTtl().toSeconds()).toString())
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .<Void>build();
                }))
                .onErrorResume(e -> {
                    if (e instanceof HomeserverClient.Refused r) {
                        return Mono.just(ResponseEntity.status(r.status()).header(HttpHeaders.CACHE_CONTROL, "no-store").<Void>build());
                    }
                    log.warn("[login] homeserver error: {}", e.toString());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).<Void>build());
                });
    }

    @PostMapping("/bff/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange ex) {
        var s = sessions.from(ex);
        s.ifPresent(x -> sessions.remove(x.id()));
        Mono<Void> end = s.map(x -> homeserver.logout(x.token())).orElse(Mono.empty());
        return end.then(Mono.fromSupplier(() -> ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(SessionStore.COOKIE, "", 0).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .<Void>build()));
    }

    /** The session the UI shows. The homeserver is asked again each time, so a revoked token ends the session. */
    @GetMapping("/bff/user/session")
    public Mono<ResponseEntity<Map<String, Object>>> session(ServerWebExchange ex) {
        var s = sessions.from(ex);
        if (s.isEmpty()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CACHE_CONTROL, "no-store").build());
        }
        return homeserver.whoami(s.get().token())
                .map(id -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view(s.get())))
                .onErrorResume(e -> {
                    if (HomeserverClient.isUnauthorized(e)) {
                        sessions.remove(s.get().id());
                        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .header(HttpHeaders.SET_COOKIE, cookie(SessionStore.COOKIE, "", 0).toString()).build());
                    }
                    log.warn("[session] cannot reach the homeserver: {}", e.toString());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).build());
                });
    }

    /** The shape the UI's SessionService reads (HugSession). */
    static Map<String, Object> view(SessionStore.Session s) {
        List<String> roles = new ArrayList<>(List.of("user"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.userId());
        m.put("label", s.label());
        m.put("provider", "matrix");
        m.put("roles", roles);
        m.put("registrationComplete", true);
        return m;
    }

    private ResponseCookie cookie(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(props.secureCookies())
                .sameSite("Lax").path("/").maxAge(maxAgeSeconds).build();
    }
}
