package dev.brickfolio.listerizer.item;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

@Configuration
class ItemRequestModule {

    abstract static class ItemRequestMixIn {
        @JsonDeserialize(using = CreateTimeDeserializer.class)
        abstract String createTime();
    }

    @Bean
    JacksonModule createTimeModule() {
        return new SimpleModule().setMixInAnnotation(ItemRequest.class, ItemRequestMixIn.class);
    }
}
