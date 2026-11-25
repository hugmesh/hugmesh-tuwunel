package net.hugmesh.tuwunel.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A second brake on password guessing, in front of the homeserver's own: a small sliding window per account name and per
 * client address. The account limit is the tight one (a person mistypes a few times, a guesser tries hundreds); the address
 * limit is loose because behind an onion service or a reverse proxy many people share one address.
 */
@Component
public class LoginLimiter {

    static final int PER_ACCOUNT = 10;
    static final int PER_ADDRESS = 60;
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_KEYS = 20_000;

    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public LoginLimiter() {
        this(Clock.systemUTC());
    }

    LoginLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records an attempt; false when it must be refused. Only attempts that are allowed count. */
    public boolean allow(String address, String account) {
        Instant now = clock.instant();
        if (hits.size() > MAX_KEYS) hits.values().removeIf(d -> d.isEmpty() || d.peekLast().isBefore(now.minus(WINDOW)));
        String a = "a:" + (address == null ? "?" : address);
        String u = "u:" + (account == null ? "" : account.trim().toLowerCase());
        synchronized (this) {
            if (count(a, now) >= PER_ADDRESS || count(u, now) >= PER_ACCOUNT) return false;
            hits.computeIfAbsent(a, k -> new ArrayDeque<>()).addLast(now);
            hits.computeIfAbsent(u, k -> new ArrayDeque<>()).addLast(now);
            return true;
        }
    }

    private int count(String key, Instant now) {
        Deque<Instant> d = hits.get(key);
        if (d == null) return 0;
        while (!d.isEmpty() && d.peekFirst().isBefore(now.minus(WINDOW))) d.pollFirst();
        return d.size();
    }
}
