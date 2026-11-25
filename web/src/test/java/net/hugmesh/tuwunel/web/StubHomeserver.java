package net.hugmesh.tuwunel.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/** A tiny stand-in for the homeserver: login, whoami, profile, logout, and an echo for everything else. */
final class StubHomeserver {

    record Seen(String method, String path, String query, String authorization, String host, String contentType, String body) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile boolean tokenRevoked;
    volatile int loginStatus = 200;
    private DisposableServer server;

    int start() {
        server = HttpServer.create().host("127.0.0.1").port(0).route(r -> r.route(q -> true, this::handle)).bindNow();
        return server.port();
    }

    void stop() { if (server != null) server.disposeNow(); }

    Seen last(String path) {
        for (int i = seen.size() - 1; i >= 0; i--) if (seen.get(i).path().equals(path)) return seen.get(i);
        return null;
    }

    private Mono<Void> handle(HttpServerRequest q, HttpServerResponse s) {
        return q.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("").flatMap(body -> {
            String uri = q.uri();
            int i = uri.indexOf('?');
            String path = i < 0 ? uri : uri.substring(0, i);
            Seen sn = new Seen(q.method().name(), path, i < 0 ? "" : uri.substring(i + 1), q.requestHeaders().get("Authorization"),
                    q.requestHeaders().get("Host"), q.requestHeaders().get("Content-Type"), body);
            seen.add(sn);
            if (path.equals("/_matrix/client/v3/login")) {
                if (loginStatus == 429) return json(s, 429, "{\"errcode\":\"M_LIMIT_EXCEEDED\"}");
                if (loginStatus == 500) return json(s, 500, "{\"errcode\":\"M_UNKNOWN\"}");
                if (body.contains("\"password\":\"right-password\"") && body.contains("\"user\":\"alice\"")) {
                    return json(s, 200, "{\"access_token\":\"tok-1\",\"user_id\":\"@alice:example.org\",\"device_id\":\"D1\"}");
                }
                return json(s, 403, "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"Invalid username or password\"}");
            }
            if (path.equals("/_matrix/client/v3/account/whoami")) {
                if (tokenRevoked || !"Bearer tok-1".equals(sn.authorization())) return json(s, 401, "{\"errcode\":\"M_UNKNOWN_TOKEN\"}");
                return json(s, 200, "{\"user_id\":\"@alice:example.org\"}");
            }
            if (path.startsWith("/_matrix/client/v3/profile/")) return json(s, 200, "{\"displayname\":\"Alice\"}");
            if (path.equals("/_matrix/client/v3/logout")) return json(s, 200, "{}");
            if (path.equals("/_matrix/client/v3/needs-token") && tokenRevoked) return json(s, 401, "{\"errcode\":\"M_UNKNOWN_TOKEN\"}");
            String echo = "{\"path\":\"" + path + "\",\"auth\":\"" + nz(sn.authorization()) + "\",\"host\":\"" + nz(sn.host())
                    + "\",\"query\":\"" + sn.query() + "\",\"body\":\"" + body.replace("\"", "'").replace("\r", "").replace("\n", "|") + "\"}";
            return s.header("Set-Cookie", "hs=1; Path=/").header("Content-Type", "application/json").sendString(Mono.just(echo)).then();
        });
    }

    private static Mono<Void> json(HttpServerResponse s, int status, String body) {
        return s.status(status).header("Content-Type", "application/json").sendString(Mono.just(body)).then();
    }

    private static String nz(String v) { return v == null ? "" : v; }
}
