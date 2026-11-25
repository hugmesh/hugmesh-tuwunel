package net.hugmesh.tuwunel.web;

import java.net.URI;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses a state-changing request to this service that a browser made on behalf of another site.
 * The session cookie is SameSite=Lax, which already keeps it off cross-site POSTs; this checks the
 * headers a browser always sends (Sec-Fetch-Site, Origin) as a second layer. Requests from non-browser
 * clients send neither and are not a CSRF concern.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CsrfGuardFilter implements WebFilter {

    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
    private final WebProperties props;

    public CsrfGuardFilter(WebProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange ex, WebFilterChain chain) {
        String path = ex.getRequest().getPath().value();
        if (!path.startsWith("/bff/") || SAFE.contains(ex.getRequest().getMethod())) {
            return chain.filter(ex);
        }
        if (crossSite(ex.getRequest().getHeaders())) {
            ex.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return ex.getResponse().setComplete();
        }
        return chain.filter(ex);
    }

    boolean crossSite(HttpHeaders h) {
        String site = h.getFirst("Sec-Fetch-Site");
        if (site != null) {
            return !(site.equalsIgnoreCase("same-origin") || site.equalsIgnoreCase("none"));
        }
        String origin = h.getFirst(HttpHeaders.ORIGIN);
        if (origin == null) return false;
        try {
            URI o = URI.create(origin);
            URI pub = URI.create(props.publicUrl());
            return !(pub.getScheme().equalsIgnoreCase(o.getScheme()) && pub.getAuthority().equalsIgnoreCase(o.getAuthority()));
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
