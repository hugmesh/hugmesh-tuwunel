package net.hugmesh.tuwunel.web;

import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** A test client that opens a new connection per request, so a connection one test cancelled cannot be reused by the next. */
final class NoPool {
    private NoPool() {}

    static ClientHttpConnector connector() {
        return new ReactorClientHttpConnector(HttpClient.create(ConnectionProvider.newConnection()));
    }
}
