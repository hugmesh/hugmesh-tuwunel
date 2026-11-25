package net.hugmesh.tuwunel.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Server-side sessions: the browser holds only an opaque random id in an HttpOnly cookie, and the
 * homeserver access token never leaves this process. In memory, which is right for one instance; a restart
 * signs everyone out (they sign in again).
 */
@Component
public class SessionStore {

    public static final String COOKIE = "bff_session";
    private static final int MAX_SESSIONS = 10_000;

    /** @param token the homeserver access token; never serialised. */
    public record Session(String id, String token, String userId, String label, Instant expires) {
        @Override public String toString() { return "Session[" + userId + "]"; }
    }

    private final WebProperties props;
    private final Clock clock;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public SessionStore(WebProperties props) {
        this(props, Clock.systemUTC());
    }

    SessionStore(WebProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    private String token() {
        byte[] b = new byte[32];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public Session create(String accessToken, String userId, String label) {
        sweep();
        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("too many open sessions");
        }
        Session s = new Session(token(), accessToken, userId, label, clock.instant().plus(props.sessionTtl()));
        sessions.put(s.id(), s);
        return s;
    }

    public Optional<Session> find(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        Session s = sessions.get(id);
        if (s == null) return Optional.empty();
        if (!s.expires().isAfter(clock.instant())) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public Optional<Session> from(ServerWebExchange ex) {
        HttpCookie c = ex.getRequest().getCookies().getFirst(COOKIE);
        return c == null ? Optional.empty() : find(c.getValue());
    }

    public void remove(String id) {
        if (id != null) sessions.remove(id);
    }

    private void sweep() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !s.expires().isAfter(now));
    }

    int sessionCount() { return sessions.size(); }
}
