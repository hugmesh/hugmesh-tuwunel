package net.hugmesh.tuwunel.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reverse proxy to the homeserver, in two modes.
 *
 * <ul>
 *   <li><b>Signed-in API</b> ({@code /bff/user/matrix/**}): the request is
 *       forwarded to the homeserver's {@code /api/**} with the session's access token. The browser never holds
 *       the token and never sends an Authorization header of its own.</li>
 *   <li><b>Passthrough</b> (the paths the homeserver itself must answer on this origin: its API for other
 *       Matrix clients, federation, media): forwarded as they are, with the client's own
 *       Authorization header and the original Host, because remote servers sign requests for that host.</li>
 * </ul>
 * Responses stream, so server-sent events and large media are not buffered.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProxyFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    static final String API_PREFIX = "/bff/user/matrix";

    /** What the homeserver answers on this origin: the client API, federation, media and the well-known discovery files. */
    static final List<String> PASSTHROUGH = List.of("/_matrix/", "/.well-known/matrix/");

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "expect");

    private final WebProperties props;
    private final SessionStore sessions;
    private final HomeserverClient homeserver;

    public ProxyFilter(WebProperties props, SessionStore sessions, HomeserverClient homeserver) {
        this.props = props;
        this.sessions = sessions;
        this.homeserver = homeserver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
        String path = ex.getRequest().getPath().value();
        if (path.equals(API_PREFIX) || path.startsWith(API_PREFIX + "/")) {
            return signedIn(ex, path.substring(API_PREFIX.length()),
                    ex.getRequest().getURI().getRawPath().substring(API_PREFIX.length()));
        }
        if (isPassthrough(path)) {
            return forward(ex, ex.getRequest().getURI().getRawPath(), null);
        }
        return chain.filter(ex);
    }

    static boolean isPassthrough(String path) {
        for (String p : PASSTHROUGH) {
            if (p.endsWith("/") ? path.startsWith(p) : (path.equals(p) || path.startsWith(p + "/"))) return true;
        }
        return false;
    }

    private Mono<Void> signedIn(ServerWebExchange ex, String upstreamPath, String rawUpstream) {
        var session = sessions.from(ex);
        if (session.isEmpty()) {
            return status(ex, HttpStatus.UNAUTHORIZED);
        }
        // Only the Matrix API is reachable this way, and only by a clean path.
        String rawLower = ex.getRequest().getURI().getRawPath().toLowerCase(Locale.ROOT);
        if (!upstreamPath.startsWith("/_matrix/") || upstreamPath.contains("..") || upstreamPath.contains("//")
                || rawLower.contains("%2e") || rawLower.contains("%2f") || rawLower.contains("%5c")) {
            return status(ex, HttpStatus.BAD_REQUEST);
        }
        return forward(ex, rawUpstream, session.get());
    }

    private Mono<Void> forward(ServerWebExchange ex, String rawUpstreamPath, SessionStore.Session session) {
        ServerHttpRequest req = ex.getRequest();
        String query = req.getURI().getRawQuery();
        URI target = URI.create(props.homeserverUrl() + rawUpstreamPath + (query == null ? "" : "?" + query));
        HttpMethod method = req.getMethod();
        boolean hasBody = req.getHeaders().getContentLength() > 0 || req.getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING);

        var spec = homeserver.http().method(method).uri(target).headers(out -> {
            req.getHeaders().forEach((name, values) -> {
                String n = name.toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(n) || n.equals("cookie") || n.equals("x-forwarded-for")
                        || n.equals("x-forwarded-host") || n.equals("x-forwarded-proto")
                        || (session != null && n.equals("authorization"))) return;
                out.addAll(name, values);
            });
            String cookies = foreignCookies(req);
            if (session == null && !cookies.isEmpty()) out.set(HttpHeaders.COOKIE, cookies);
            if (session != null) out.setBearerAuth(session.token());
            String host = req.getHeaders().getFirst(HttpHeaders.HOST);
            out.set(HttpHeaders.HOST, host != null ? host : java.net.URI.create(props.publicUrl()).getAuthority());
            out.set("X-Forwarded-Host", host != null ? host : java.net.URI.create(props.publicUrl()).getAuthority());
            out.set("X-Forwarded-Proto", java.net.URI.create(props.publicUrl()).getScheme());
            var remote = req.getRemoteAddress();
            if (remote != null && remote.getAddress() != null) out.set("X-Forwarded-For", remote.getAddress().getHostAddress());
        });

        var request = hasBody ? spec.body(BodyInserters.fromDataBuffers(req.getBody())) : spec;
        return request.exchangeToMono(cr -> relay(ex, cr, session))
                // the homeserver down, refusing connections or timing out: a gateway error, not a 500 and not a hang.
                // (Once the response has started there is nothing left to change; the connection just ends.)
                .onErrorResume(e -> {
                    if (ex.getResponse().isCommitted()) return Mono.error(e);
                    log.warn("[proxy] {} {} failed: {}", method, rawUpstreamPath, e.toString());
                    return status(ex, HttpStatus.BAD_GATEWAY);
                });
    }

    private Mono<Void> relay(ServerWebExchange ex, ClientResponse cr, SessionStore.Session session) {
        var res = ex.getResponse();
        HttpStatusCode status = cr.statusCode();
        // A token the homeserver no longer honours ends the sign-in, instead of leaving a session that cannot do anything.
        if (session != null && status.value() == 401) {
            sessions.remove(session.id());
            res.addCookie(ResponseCookie.from(SessionStore.COOKIE, "").path("/").maxAge(0).httpOnly(true)
                    .secure(props.secureCookies()).sameSite("Lax").build());
        }
        res.setStatusCode(status);
        cr.headers().asHttpHeaders().forEach((name, values) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(n)) return;
            if (session != null && n.equals("set-cookie")) return; // the homeserver never sets cookies on the signed-in API
            res.getHeaders().addAll(name, values);
        });
        Flux<DataBuffer> body = cr.bodyToFlux(DataBuffer.class);
        MediaType type = cr.headers().contentType().orElse(null);
        if (type != null && type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
            return res.writeAndFlushWith(body.map(Mono::just));
        }
        return res.writeWith(body);
    }

    /** Cookies for the homeserver on a passthrough request: everything except this service's own. */
    private static String foreignCookies(ServerHttpRequest req) {
        StringBuilder sb = new StringBuilder();
        for (List<HttpCookie> list : req.getCookies().values()) {
            for (HttpCookie c : list) {
                if (c.getName().equals(SessionStore.COOKIE)) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(c.getName()).append('=').append(c.getValue());
            }
        }
        return sb.toString();
    }

    private static Mono<Void> status(ServerWebExchange ex, HttpStatus s) {
        ex.getResponse().setStatusCode(s);
        ex.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        return ex.getResponse().setComplete();
    }
}
