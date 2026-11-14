package com.togethermarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binds the real profiles without opening a database connection or resolving Google credentials.
 */
class CloudSqlConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
          .withPropertyValues(
              "spring.config.location=file:src/main/resources/application.properties",
              "DB_USERNAME=configuration_test_user",
              "DB_PASSWORD=configuration-test-only");

  @Test
  void localProfileKeepsExistingJdbcConnectionWithoutCloudProperties() {
    runner
        .withPropertyValues(
            "spring.profiles.active=local", "DB_URL=jdbc:mysql://localhost:3306/configuration_test")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var dataSource = context.getBean(HikariDataSource.class);
              assertThat(dataSource.getJdbcUrl())
                  .isEqualTo("jdbc:mysql://localhost:3306/configuration_test");
              assertThat(dataSource.getDataSourceProperties()).doesNotContainKey("socketFactory");
              assertThat(dataSource.getHikariPoolMXBean()).isNull();
            });
  }

  @Test
  void cloudProfileUsesConnectorAndBoundedPoolWhileRetainingDemoFeatures() {
    cloud()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var ds = context.getBean(HikariDataSource.class);
              assertThat(ds.getJdbcUrl())
                  .isEqualTo("jdbc:mysql:///configuration_test?connectionTimeZone=Asia/Seoul");
              assertThat(ds.getJdbcUrl()).doesNotContain("password", "localhost");
              assertThat(ds.getUsername()).isEqualTo("configuration_test_user");
              assertThat(ds.getDataSourceProperties())
                  .containsEntry("socketFactory", "com.google.cloud.sql.mysql.SocketFactory")
                  .containsEntry("cloudSqlInstance", "test-project:asia-northeast3:test-instance")
                  .containsEntry("ipTypes", "PUBLIC")
                  .containsEntry("cloudSqlRefreshStrategy", "lazy");
              assertThat(ds.getMaximumPoolSize()).isEqualTo(5);
              assertThat(ds.getMinimumIdle()).isZero();
              assertThat(ds.getConnectionTimeout()).isEqualTo(10000);
              assertThat(ds.getHikariPoolMXBean()).isNull();
              assertThat(context.getEnvironment().getProperty("recommendation.api.enabled"))
                  .isEqualTo("true");
              assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                  .isEqualTo("validate");
            });
  }

  @Test
  void privateNetworkSelectionCanBeExplicitlyConfigured() {
    cloud()
        .withPropertyValues("CLOUD_SQL_IP_TYPES=PRIVATE")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(HikariDataSource.class).getDataSourceProperties())
                  .containsEntry("ipTypes", "PRIVATE");
            });
  }

  @Test
  void connectorClassesAreAvailableWithoutInitializingCredentials() throws Exception {
    var loader = getClass().getClassLoader();
    assertThat(
            Class.forName("com.google.cloud.sql.mysql.SocketFactory", false, loader)
                .getInterfaces())
        .isNotEmpty();
    assertThat(Class.forName("com.mysql.cj.jdbc.Driver", false, loader)).isNotNull();
  }

  private ApplicationContextRunner cloud() {
    return runner.withPropertyValues(
        "spring.profiles.active=demo,cloudsql",
        "DB_NAME=configuration_test",
        "CLOUD_SQL_INSTANCE_CONNECTION_NAME=test-project:asia-northeast3:test-instance",
        "DB_URL=jdbc:mysql://localhost:3306/unused_local_database");
  }
}
