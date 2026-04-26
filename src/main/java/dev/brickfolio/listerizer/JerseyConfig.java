package dev.brickfolio.listerizer;

import dev.brickfolio.listerizer.api.ItemController;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.stereotype.Component;

@Component
public class JerseyConfig extends ResourceConfig {

    public JerseyConfig() {
        register(ItemController.class);
        register(ValidationExceptionMapper.class);
        register(JacksonExceptionMapper.class);
    }
}
