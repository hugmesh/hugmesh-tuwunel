package net.hugmesh.tuwunel.web;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param homeserverUrl where Tuwunel listens, as this service reaches it (the compose service name).
 * @param publicUrl     the origin people type into the browser, e.g. https://chat.example.org. The base of the CSRF
 *                      origin check; its scheme decides the cookies' Secure flag.
 * @param uiDir         the built web UI (index.html and assets).
 * @param stateDir      where per-account preferences are kept.
 * @param sessionTtl    how long a sign-in lasts.
 * @param deviceName    the device name the homeserver lists for a session this service opens.
 */
@ConfigurationProperties("hugmesh.web")
public record WebProperties(
        @DefaultValue("http://tuwunel:8008") String homeserverUrl,
        @DefaultValue("http://localhost:8080") String publicUrl,
        @DefaultValue("/app/ui") Path uiDir,
        @DefaultValue("/var/lib/hugmesh-web") Path stateDir,
        @DefaultValue("12h") Duration sessionTtl,
        @DefaultValue("HugMesh web") String deviceName) {

    public WebProperties {
        homeserverUrl = stripTrailingSlash(homeserverUrl);
        publicUrl = stripTrailingSlash(publicUrl);
        URI u = URI.create(publicUrl);
        if (u.getScheme() == null || u.getHost() == null) {
            throw new IllegalArgumentException("hugmesh.web.public-url must be an absolute http(s) URL: " + publicUrl);
        }
    }

    public boolean secureCookies() {
        return "https".equalsIgnoreCase(URI.create(publicUrl).getScheme());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
