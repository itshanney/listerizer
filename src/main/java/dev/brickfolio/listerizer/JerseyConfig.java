package dev.brickfolio.listerizer;

import dev.brickfolio.listerizer.item.ItemController;
import dev.brickfolio.listerizer.item.JacksonExceptionMapper;
import dev.brickfolio.listerizer.item.ValidationExceptionMapper;
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
