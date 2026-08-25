package com.fbrl.global.config;

import com.fbrl.adapter.in.web.JwtAuthenticationFilter;
import com.fbrl.adapter.in.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final CorsConfigurationSource corsConfigurationSource;
  private final ObjectMapper objectMapper;
  private final PrometheusMetricsCredentialsProperties prometheusMetricsCredentialsProperties;

  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      CorsConfigurationSource corsConfigurationSource,
      ObjectMapper objectMapper,
      PrometheusMetricsCredentialsProperties prometheusMetricsCredentialsProperties) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.corsConfigurationSource = corsConfigurationSource;
    this.objectMapper = objectMapper;
    this.prometheusMetricsCredentialsProperties = prometheusMetricsCredentialsProperties;
  }

  @Bean
  @Order(1)
  public SecurityFilterChain prometheusSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/actuator/prometheus")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults())
        .userDetailsService(prometheusUserDetailsService());
    return http.build();
  }

  private InMemoryUserDetailsManager prometheusUserDetailsService() {
    InMemoryUserDetailsManager userDetailsManager = new InMemoryUserDetailsManager();
    String username = prometheusMetricsCredentialsProperties.username();
    String password = prometheusMetricsCredentialsProperties.password();
    if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
      userDetailsManager.createUser(
          User.withUsername(username)
              .password(passwordEncoder().encode(password))
              .roles("PROMETHEUS")
              .build());
    }
    return userDetailsManager;
  }

  @Bean
  @Order(2)
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(accessDeniedHandler()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/login", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/api/v1/demo/**")
                    .hasAnyRole("DEMO", "ADMIN")
                    .anyRequest()
                    .hasRole("ADMIN"))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  private AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, authException) ->
        writeErrorResponse(
            response,
            HttpServletResponse.SC_UNAUTHORIZED,
            ErrorResponse.of("UNAUTHORIZED", "인증이 필요합니다. 로그인 후 토큰을 포함해 다시 요청하세요."));
  }

  private AccessDeniedHandler accessDeniedHandler() {
    return (request, response, accessDeniedException) ->
        writeErrorResponse(
            response,
            HttpServletResponse.SC_FORBIDDEN,
            ErrorResponse.of("FORBIDDEN", "이 작업을 수행할 권한이 없습니다."));
  }

  private void writeErrorResponse(HttpServletResponse response, int status, ErrorResponse body)
      throws java.io.IOException {
    response.setStatus(status);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }
}
