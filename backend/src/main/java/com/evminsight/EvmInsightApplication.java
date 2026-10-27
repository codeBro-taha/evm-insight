package com.evminsight;

import com.evminsight.storage.FileStore;
import com.evminsight.storage.MigrationRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EvmInsightApplication {
    public static void main(String[] args) { SpringApplication.run(EvmInsightApplication.class, args); }

    /** Opens the versioned store; fails startup with a clear message if it is damaged, unsupported or already in use. */
    @Bean(destroyMethod = "close")
    FileStore fileStore(@Value("${evm.data-dir}") String dir) {
        return FileStore.open(Path.of(dir), new MigrationRegistry());
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
