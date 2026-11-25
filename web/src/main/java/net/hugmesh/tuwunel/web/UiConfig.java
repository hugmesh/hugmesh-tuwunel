package net.hugmesh.tuwunel.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ServerWebExchange;
import java.util.List;
import org.springframework.web.reactive.resource.ResourceResolverChain;

/**
 * Serves the built web UI from the UI directory. A path with no file behind it is a route of the
 * single-page app, so it gets index.html (which is never cached, so a new release shows at once).
 * Hashed bundles are cached for a year.
 */
@Configuration
public class UiConfig implements WebFluxConfigurer {

    private final WebProperties props;

    public UiConfig(WebProperties props) {
        this.props = props;
    }

    /** The resource handler never sees the bare "/" (an empty path), so the app shell is served here. */
    @org.springframework.context.annotation.Bean
    org.springframework.web.reactive.function.server.RouterFunction<org.springframework.web.reactive.function.server.ServerResponse> appShell() {
        Path index = props.uiDir().toAbsolutePath().normalize().resolve("index.html");
        return org.springframework.web.reactive.function.server.RouterFunctions.route()
                .GET("/", r -> Files.isRegularFile(index)
                        ? org.springframework.web.reactive.function.server.ServerResponse.ok()
                                .contentType(org.springframework.http.MediaType.TEXT_HTML).cacheControl(CacheControl.noCache())
                                .body(org.springframework.web.reactive.function.BodyInserters.fromResource(new PathResource(index)))
                        : org.springframework.web.reactive.function.server.ServerResponse.notFound().build())
                .build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = props.uiDir().toAbsolutePath().normalize();
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + root + "/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new SpaResolver(root));
    }

    /** Resolves a file under the UI root, or index.html for an app route; never leaves the root. */
    static final class SpaResolver extends PathResourceResolver {
        private final Path root;

        SpaResolver(Path root) {
            this.root = root;
        }

        @Override
        protected Mono<Resource> getResource(String resourcePath, Resource location) {
            try {
                Path want = root.resolve(resourcePath).normalize();
                if (!want.startsWith(root)) return Mono.empty();
                if (Files.isRegularFile(want)) return Mono.just(new PathResource(want));
                // API paths are never app routes: an unknown /bff or /_matrix path is a 404, not the app shell
                if (resourcePath.startsWith("bff/") || resourcePath.startsWith("_matrix/") || resourcePath.startsWith(".well-known/")) return Mono.empty();
                String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                // A request for a missing asset (it has an extension) is a 404, not the app shell.
                if (last.contains(".")) return Mono.empty();
                Path index = root.resolve("index.html");
                return Files.isRegularFile(index) ? Mono.just(new PathResource(index)) : Mono.empty();
            } catch (RuntimeException e) {
                return Mono.empty();
            }
        }
    }
}
