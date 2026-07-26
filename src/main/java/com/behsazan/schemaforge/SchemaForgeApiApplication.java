package com.behsazan.schemaforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.config.SpellCheckProperties;
import com.behsazan.schemaforge.config.GrantProperties;
import com.behsazan.schemaforge.config.MetadataProperties;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({AuditProperties.class, GrantProperties.class, SpellCheckProperties.class, MetadataProperties.class})
public class SchemaForgeApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchemaForgeApiApplication.class, args);
    }
}
