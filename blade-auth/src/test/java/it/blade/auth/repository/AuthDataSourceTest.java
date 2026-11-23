package it.blade.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthDataSourceTest {
    @Test
    void opensConnectionsThroughTheConfiguredHikariPool() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:auth-pool",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    var dataSource = context.getBean(DataSource.class);
                    assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                    assertThat(((HikariDataSource) dataSource).getPoolName()).isEqualTo("blade-auth-pool");
                    try (var connection = dataSource.getConnection()) {
                        assertThat(connection.isValid(1)).isTrue();
                    }
                });
    }
}