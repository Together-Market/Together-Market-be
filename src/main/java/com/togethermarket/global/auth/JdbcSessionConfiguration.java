package com.togethermarket.global.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.session.jdbc.enabled", havingValue = "true")
@DependsOnDatabaseInitialization
@EnableJdbcHttpSession(tableName = "app_sessions", maxInactiveIntervalInSeconds = 1800)
public class JdbcSessionConfiguration {
  @Bean
  CookieSerializer cookieSerializer(Environment environment) {
    var serializer = new DefaultCookieSerializer();
    serializer.setCookieName("JSESSIONID");
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(
        environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true));
    serializer.setSameSite(
        environment.getProperty("server.servlet.session.cookie.same-site", "None"));
    return serializer;
  }

  @Bean
  FilterRegistrationBean<SessionRepositoryFilter<?>> jdbcSessionFilterRegistration(
      SessionRepositoryFilter<?> springSessionRepositoryFilter) {
    var registration =
        new FilterRegistrationBean<SessionRepositoryFilter<?>>(springSessionRepositoryFilter);
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
    return registration;
  }
}
