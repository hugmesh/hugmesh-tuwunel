package net.hugmesh.tuwunel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The small surfaces the web UI expects next to the Matrix API: appearance preferences kept per
 * account, the "connected clients" probe, and the media-protection capability (not offered here).
 */
@RestController
public class UserCompatController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("^(matrix|mastodon)\\.appearance\\.[A-Za-z0-9.]{1,64}$");
    private static final int MAX_VALUE = 512;
    private static final int MAX_KEYS = 64;

    private final SessionStore sessions;
    private final WebProperties props;

    public UserCompatController(SessionStore sessions, WebProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    @GetMapping("/bff/user/preferences")
    public Mono<ResponseEntity<Map<String, String>>> preferences(ServerWebExchange ex) {
        var s = sessions.from(ex);
        if (s.isEmpty()) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        return Mono.fromCallable(() -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(read(s.get().userId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/bff/user/preferences")
    public Mono<ResponseEntity<Void>> setPreference(@RequestBody Map<String, String> body, ServerWebExchange ex) {
        var s = sessions.from(ex);
        if (s.isEmpty()) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        String key = body.get("key");
        String value = body.get("value");
        if (key == null || value == null || !KEY.matcher(key).matches() || value.length() > MAX_VALUE) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return Mono.fromCallable(() -> {
            synchronized (this) {
                Map<String, String> all = read(s.get().userId());
                if (!all.containsKey(key) && all.size() >= MAX_KEYS) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).<Void>build();
                all.put(key, value);
                write(s.get().userId(), all);
            }
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 200 tells the UI that other Matrix clients may connect: the Matrix API is open on this origin. */
    @GetMapping("/bff/user/connected-clients")
    public Mono<ResponseEntity<List<Object>>> connectedClients(ServerWebExchange ex) {
        if (sessions.from(ex).isEmpty()) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        return Mono.just(ResponseEntity.ok(List.of()));
    }

    /** The media-protection (AI cloak) tier is a HugMesh mesh feature; a standalone instance has none. */
    @GetMapping("/bff/user/media/capability")
    public Mono<ResponseEntity<Map<String, Object>>> capability(ServerWebExchange ex) {
        if (sessions.from(ex).isEmpty()) return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        m.put("backend", "none");
        m.put("defaultMode", "glaze");
        return Mono.just(ResponseEntity.ok(m));
    }

    // ---- storage: one small JSON file per account, named by a hash so no account name is a path ----

    private Path file(String acct) throws IOException {
        Path dir = props.stateDir().resolve("prefs");
        Files.createDirectories(dir);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(acct.getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(h) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> read(String acct) throws IOException {
        Path f = file(acct);
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return out;
        JsonNode j = JSON.readTree(Files.readString(f, StandardCharsets.UTF_8));
        Iterator<Map.Entry<String, JsonNode>> it = j.fields();
        while (it.hasNext()) {
            var e = it.next();
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    private void write(String acct, Map<String, String> all) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        all.forEach(n::put);
        Path f = file(acct);
        Path tmp = Files.createTempFile(f.getParent(), "prefs", ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(n), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
