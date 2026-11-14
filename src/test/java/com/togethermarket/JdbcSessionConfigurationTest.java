package com.togethermarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.togethermarket.global.auth.JdbcSessionConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;

class JdbcSessionConfigurationTest {
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withUserConfiguration(JdbcSessionConfiguration.class)
          .withBean(
              DataSource.class,
              () -> new DriverManagerDataSource("jdbc:h2:mem:session_configuration", "sa", ""))
          .withBean(
              PlatformTransactionManager.class,
              () ->
                  new DataSourceTransactionManager(
                      new DriverManagerDataSource("jdbc:h2:mem:session_configuration", "sa", "")));

  @Test
  void localDoesNotEnableJdbcSessions() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(SessionRepositoryFilter.class);
        });
  }

  @Test
  void deploymentRegistersJdbcFilterBeforeSecurity() {
    runner
        .withPropertyValues("app.session.jdbc.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JdbcIndexedSessionRepository.class);
              var registration =
                  context.getBean("jdbcSessionFilterRegistration", FilterRegistrationBean.class);
              assertThat(registration.getOrder()).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER);
              assertThat(registration.getFilter())
                  .isSameAs(context.getBean(SessionRepositoryFilter.class));
            });
  }
}
