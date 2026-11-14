package com.togethermarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.togethermarket.global.auth.LoginUser;
import com.togethermarket.user.entity.UserRole;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcSessionPersistenceTest {
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void anotherRepositoryRestoresAuthenticationAndCsrfAndObservesLogout() {
    var ds =
        new DriverManagerDataSource("jdbc:h2:mem:session_round_trip;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(
            new ClassPathResource("org/springframework/session/jdbc/schema-h2.sql"))
        .execute(ds);
    var jdbc = new JdbcTemplate(ds);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    org.springframework.session.SessionRepository<org.springframework.session.Session> writer =
        (org.springframework.session.SessionRepository)
            new JdbcIndexedSessionRepository(jdbc, transactions);
    org.springframework.session.SessionRepository<org.springframework.session.Session> reader =
        (org.springframework.session.SessionRepository)
            new JdbcIndexedSessionRepository(jdbc, transactions);
    var session = writer.createSession();
    var principal = new LoginUser(1L, UserRole.OWNER);
    session.setAttribute(
        "SPRING_SECURITY_CONTEXT",
        new SecurityContextImpl(
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")))));
    // Synthetic fixture only; no real user CSRF token is read or printed.
    session.setAttribute("csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-fixture"));
    writer.save(session);
    var restored = reader.findById(session.getId());
    assertThat(restored).isNotNull();
    SecurityContextImpl security = restored.getAttribute("SPRING_SECURITY_CONTEXT");
    assertThat(security.getAuthentication().getPrincipal()).isEqualTo(principal);
    DefaultCsrfToken csrf = restored.getAttribute("csrf");
    assertThat(csrf.getToken()).isEqualTo("test-fixture");
    writer.deleteById(session.getId());
    assertThat(reader.findById(session.getId())).isNull();
    assertThat(jdbc.queryForObject("select count(*) from SPRING_SESSION_ATTRIBUTES", Integer.class))
        .isZero();
    var expired = writer.createSession();
    expired.setMaxInactiveInterval(Duration.ofSeconds(1));
    expired.setLastAccessedTime(java.time.Instant.now().minusSeconds(10));
    writer.save(expired);
    assertThat(reader.findById(expired.getId())).isNull();
  }
}
