package net.hugmesh.tuwunel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** The calls this service makes to the homeserver itself (the proxy uses {@link #http()} directly). */
@Component
public class HomeserverClient {

    /** A session the homeserver opened for a user. @param token the access token; never serialised. */
    public record Login(String token, String userId) {
        @Override public String toString() { return "Login[" + userId + "]"; }
    }

    /** The homeserver said no, in a way the person at the keyboard should hear about. */
    public static final class Refused extends RuntimeException {
        private final int status;
        public Refused(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebClient http;
    private final WebProperties props;

    public HomeserverClient(WebProperties props) {
        this.props = props;
        // No redirect following: a redirect belongs to the browser.
        HttpClient netty = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000).followRedirect(false);
        this.http = WebClient.builder().baseUrl(props.homeserverUrl()).clientConnector(new ReactorClientHttpConnector(netty)).build();
    }

    public WebClient http() {
        return http;
    }

    /** m.login.password. M_FORBIDDEN (wrong user or password) is {@link Refused} 401; M_LIMIT_EXCEEDED is 429. */
    public Mono<Login> login(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "m.login.password");
        body.put("identifier", Map.of("type", "m.id.user", "user", username));
        body.put("password", password);
        body.put("initial_device_display_name", props.deviceName());
        return http.post().uri("/_matrix/client/v3/login").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(String.class).map(HomeserverClient::parse)
                .map(j -> new Login(text(j, "access_token"), text(j, "user_id")))
                .onErrorMap(WebClientResponseException.class, e -> {
                    int s = e.getStatusCode().value();
                    if (s == 403 || s == 401) return new Refused(401, "credentials refused");
                    if (s == 429) return new Refused(429, "rate limited");
                    return e;
                });
    }

    /** Who this token belongs to; an error (401/403) when the homeserver no longer honours it. */
    public Mono<String> whoami(String token) {
        return http.get().uri("/_matrix/client/v3/account/whoami").headers(h -> h.setBearerAuth(token))
                .retrieve().bodyToMono(String.class).map(HomeserverClient::parse).map(j -> text(j, "user_id"));
    }

    /** The user's display name, or the user id when there is none. Never fails. */
    public Mono<String> displayName(String userId) {
        return http.get().uri("/_matrix/client/v3/profile/{id}", userId)
                .retrieve().bodyToMono(String.class).map(HomeserverClient::parse)
                .map(j -> j.path("displayname").asText("").isBlank() ? userId : j.path("displayname").asText())
                .onErrorReturn(userId);
    }

    /** Best effort: a sign-out must succeed locally even when the homeserver is unreachable. */
    public Mono<Void> logout(String token) {
        return http.post().uri("/_matrix/client/v3/logout").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .retrieve().toBodilessEntity().then().onErrorResume(e -> Mono.empty());
    }

    public static boolean isUnauthorized(Throwable e) {
        return e instanceof WebClientResponseException w && statusIn(w.getStatusCode(), 401, 403);
    }

    private static boolean statusIn(HttpStatusCode c, int... any) {
        for (int a : any) if (c.value() == a) return true;
        return false;
    }

    static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("the homeserver returned a body that is not JSON", e);
        }
    }

    static String text(JsonNode j, String field) {
        JsonNode n = j.get(field);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            throw new IllegalStateException("the homeserver's reply has no '" + field + "'");
        }
        return n.asText();
    }
}
