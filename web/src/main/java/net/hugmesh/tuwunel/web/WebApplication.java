package net.hugmesh.tuwunel.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Front door for a standalone Tuwunel: the Matrix web UI, sign-in with a Matrix username and password,
 *  the Matrix client API with the user's token held server-side, and the homeserver's federation paths. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
