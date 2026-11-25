package net.hugmesh.tuwunel.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApplicationTest {

    static final StubHomeserver HS = new StubHomeserver();
    static int hsPort;
    static Path ui;
    static Path state;

    @AfterAll
    static void down() { HS.stop(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        synchronized (WebApplicationTest.class) {
            if (hsPort == 0) {
                hsPort = HS.start();
                ui = Files.createTempDirectory("ui");
                state = Files.createTempDirectory("state");
                Files.writeString(ui.resolve("index.html"), "<html>app shell</html>");
                Files.createDirectories(ui.resolve("assets"));
                Files.writeString(ui.resolve("assets/app-abc123.js"), "console.log(1)");
            }
        }
        r.add("hugmesh.web.homeserver-url", () -> "http://127.0.0.1:" + hsPort);
        r.add("hugmesh.web.public-url", () -> "http://chat.test");
        r.add("hugmesh.web.ui-dir", () -> ui.toString());
        r.add("hugmesh.web.state-dir", () -> state.toString());
    }

    @LocalServerPort int webPort;
    WebTestClient web;

    @BeforeEach
    void client() {
        HS.seen.clear();
        HS.tokenRevoked = false;
        HS.loginStatus = 200;
        web = WebTestClient.bindToServer(NoPool.connector()).baseUrl("http://127.0.0.1:" + webPort)
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    private static String cookieValue(EntityExchangeResult<?> r, String name) {
        List<String> all = r.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        if (all == null) return null;
        for (String c : all) if (c.startsWith(name + "=")) return c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';'));
        return null;
    }

    /** A unique account name per call keeps the per-account limiter out of unrelated tests. */
    private String signIn() {
        var r = web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "alice", "password", "right-password")).exchange()
                .expectStatus().isNoContent().expectBody().isEmpty();
        String sid = cookieValue(r, SessionStore.COOKIE);
        assertThat(sid).isNotBlank();
        return sid;
    }

    @Test
    void signInSendsTheCredentialsOnceAndKeepsOnlyACookie() {
        var r = web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "alice", "password", "right-password")).exchange()
                .expectStatus().isNoContent().expectBody().isEmpty();
        String sid = cookieValue(r, SessionStore.COOKIE);
        assertThat(sid).isNotBlank().doesNotContain("tok-1");
        assertThat(r.getResponseHeaders().get(HttpHeaders.SET_COOKIE).get(0)).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        var seen = HS.last("/_matrix/client/v3/login");
        assertThat(seen.body()).contains("m.login.password").contains("\"user\":\"alice\"");
        var session = web.get().uri("/bff/user/session").cookie(SessionStore.COOKIE, sid).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(session).contains("\"id\":\"@alice:example.org\"").contains("\"label\":\"Alice\"").contains("\"provider\":\"matrix\"")
                .doesNotContain("tok-1").doesNotContain("right-password");
    }

    @Test
    void wrongCredentialsAre401AndNoSession() {
        var r = web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "mallory", "password", "nope")).exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
        assertThat(cookieValue(r, SessionStore.COOKIE)).isNull();
    }

    @Test
    void theHomeserversOwnRateLimitAndOutagesAreReportedAsSuch() {
        HS.loginStatus = 429;
        web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("username", "limited1", "password", "x")).exchange().expectStatus().isEqualTo(429);
        HS.loginStatus = 500;
        web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("username", "broken1", "password", "x")).exchange().expectStatus().isEqualTo(502);
    }

    @Test
    void emptyOrOversizedCredentialsNeverReachTheHomeserver() {
        HS.seen.clear();
        for (Map<String, String> bad : List.of(Map.of("username", "", "password", "x"), Map.of("username", "a", "password", ""),
                Map.of("username", "a"), Map.of("username", "x".repeat(600), "password", "p"))) {
            web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON).bodyValue(bad).exchange().expectStatus().isBadRequest();
        }
        assertThat(HS.last("/_matrix/client/v3/login")).isNull();
    }

    @Test
    void guessingOneAccountIsStoppedByTheServiceItself() {
        int refused = 0;
        for (int i = 0; i < LoginLimiter.PER_ACCOUNT + 5; i++) {
            int s = web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("username", "victim-account", "password", "guess" + i)).exchange().returnResult(Void.class).getStatus().value();
            if (s == 429) refused++;
        }
        assertThat(refused).isGreaterThanOrEqualTo(5);
        // and the homeserver only ever saw the allowed attempts
        long reached = HS.seen.stream().filter(x -> x.path().equals("/_matrix/client/v3/login")).count();
        assertThat(reached).isLessThanOrEqualTo(LoginLimiter.PER_ACCOUNT);
    }

    @Test
    void noSessionIs401() {
        web.get().uri("/bff/user/session").exchange().expectStatus().isUnauthorized();
        web.get().uri("/bff/user/matrix/_matrix/client/v3/sync").exchange().expectStatus().isUnauthorized();
        web.get().uri("/bff/user/preferences").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theSignedInApiSendsTheServerHeldTokenAndNothingTheBrowserSent() {
        String sid = signIn();
        HS.seen.clear();
        var body = web.get().uri("/bff/user/matrix/_matrix/client/v3/sync?timeout=30000&since=s1")
                .cookie(SessionStore.COOKIE, sid).header(HttpHeaders.AUTHORIZATION, "Bearer attacker-supplied")
                .header(HttpHeaders.HOST, "chat.test").exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(body.getResponseBody()).contains("\"path\":\"/_matrix/client/v3/sync\"").contains("\"auth\":\"Bearer tok-1\"")
                .contains("timeout=30000&since=s1").contains("\"host\":\"chat.test\"");
        assertThat(body.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void onlyTheMatrixApiIsReachableThroughTheSignedInProxy() {
        String sid = signIn();
        for (String p : List.of("/bff/user/matrix/_synapse/admin/v1/users", "/bff/user/matrix/_matrix/../_synapse/x",
                "/bff/user/matrix/_matrix/%2e%2e/x", "/bff/user/matrix/_matrix//x", "/bff/user/matrix/_matrix%2fclient/x")) {
            web.get().uri(URI.create("http://127.0.0.1:" + webPort + p)).cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void aRevokedTokenEndsTheSession() {
        String sid = signIn();
        HS.tokenRevoked = true;
        var r = web.get().uri("/bff/user/matrix/_matrix/client/v3/needs-token").cookie(SessionStore.COOKIE, sid).exchange()
                .expectStatus().isUnauthorized().expectBody().returnResult();
        assertThat(cookieValue(r, SessionStore.COOKIE)).isEmpty();
        HS.tokenRevoked = false;
        web.get().uri("/bff/user/session").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void passthroughKeepsTheClientsOwnAuthorizationAndTheHost() {
        HS.seen.clear();
        var body = web.get().uri("/_matrix/client/v3/versions").header(HttpHeaders.AUTHORIZATION, "Bearer an-element-clients-own-token")
                .header(HttpHeaders.HOST, "chat.test").exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(body.getResponseBody()).contains("\"auth\":\"Bearer an-element-clients-own-token\"").contains("\"host\":\"chat.test\"");
        for (String p : List.of("/.well-known/matrix/server", "/.well-known/matrix/client", "/_matrix/federation/v1/version", "/_matrix/media/v3/download/x/y")) {
            web.get().uri(p).exchange().expectStatus().isOk();
        }
    }

    @Test
    void logoutEndsTheSessionAndTheHomeserversToo() {
        String sid = signIn();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isNoContent();
        web.get().uri("/bff/user/session").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isUnauthorized();
        assertThat(HS.last("/_matrix/client/v3/logout").authorization()).isEqualTo("Bearer tok-1");
    }

    @Test
    void aCrossSitePostIsRefused() {
        String sid = signIn();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).header("Sec-Fetch-Site", "cross-site").exchange().expectStatus().isForbidden();
        web.post().uri("/bff/logout").cookie(SessionStore.COOKIE, sid).header(HttpHeaders.ORIGIN, "https://evil.example").exchange().expectStatus().isForbidden();
        web.post().uri("/bff/login").contentType(MediaType.APPLICATION_JSON).header("Sec-Fetch-Site", "cross-site")
                .bodyValue(Map.of("username", "alice", "password", "right-password")).exchange().expectStatus().isForbidden();
    }

    @Test
    void preferencesRoundTripAndRejectBadKeys() {
        String sid = signIn();
        web.put().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("key", "matrix.appearance.theme", "value", "dark")).exchange().expectStatus().isNoContent();
        web.get().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$['matrix.appearance.theme']").isEqualTo("dark");
        for (String bad : List.of("other.key", "matrix.appearance.", "../../etc/passwd")) {
            web.put().uri("/bff/user/preferences").cookie(SessionStore.COOKIE, sid).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("key", bad, "value", "x")).exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void uiIsServedWithAnAppShellFallbackAndApiPathsNeverBecomeTheShell() {
        web.get().uri("/").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("<html>app shell</html>");
        web.get().uri("/rooms/abc").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("<html>app shell</html>");
        web.get().uri("/assets/app-abc123.js").exchange().expectStatus().isOk();
        web.get().uri("/assets/missing.js").exchange().expectStatus().isNotFound();
        web.get().uri("/bff/user/no-such-endpoint").exchange().expectStatus().isNotFound();
    }

    @Test
    void healthIsUpWithoutAHomeserver() {
        web.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
