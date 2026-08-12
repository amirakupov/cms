package com.cms.config;

import com.cms.repo.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;

@Configuration
public class ApplicationConfig{

    private final UserRepository repository;

    public ApplicationConfig(UserRepository repository) {
        this.repository = repository;
    }

    @Bean
    public UserDetailsService userDetailsService(){
        return username -> repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("user not found"));
    }

    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService uds, BCryptPasswordEncoder enc) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(enc);
        return provider;
    }

    @Bean
    BCryptPasswordEncoder encoder(){
        return new BCryptPasswordEncoder();
    }
    @Bean
    AuthenticationManager authManager(AuthenticationConfiguration authConfiguraton) throws Exception{
        return authConfiguraton.getAuthenticationManager() ;
    }

    /** Injected rather than called statically so the cadence gate can be tested at a fixed instant. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Boot 4 ships Jackson 3 and auto-configures a tools.jackson.databind.ObjectMapper, but the
     * services that parse third-party JSON by hand are written against Jackson 2, which is only
     * on the classpath transitively via jjwt-jackson. Without this bean the context fails to
     * start with "required a bean of type com.fasterxml.jackson.databind.ObjectMapper".
     */
    @Bean
    com.fasterxml.jackson.databind.ObjectMapper legacyObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

}

