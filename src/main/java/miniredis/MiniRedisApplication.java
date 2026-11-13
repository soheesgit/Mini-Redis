package miniredis;

import miniredis.service.MiniRedisService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MiniRedisApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiniRedisApplication.class, args);
    }

    @Bean
    MiniRedisService miniRedisService() {
        return new MiniRedisService(1024);
    }
}
