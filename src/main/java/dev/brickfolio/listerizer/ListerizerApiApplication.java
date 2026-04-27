package dev.brickfolio.listerizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("dev.brickfolio.listerizer.domain")
@EnableJpaRepositories
@Configuration
public class ListerizerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ListerizerApiApplication.class, args);
    }
}
