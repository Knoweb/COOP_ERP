package lk.coopfed.knoweb.kernel.internal.stub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web wiring every module inherits, so that no controller configures it again:
 * the {@code ScopeContext} controller parameter, and cross-origin access for the web client.
 *
 * <p>CORS is one filter in one place, never {@code @CrossOrigin} on a controller, and never
 * the origin "*": the allowed origins are configuration (coop-erp.web.allowed-origins).
 * It is a servlet filter registered first, not the MVC mapping, because the idempotency
 * filter can answer a request itself, and that answer needs the CORS headers too.
 */
@Configuration
public class KernelWebConfig implements WebMvcConfigurer {

    private final DevScopeArgumentResolver scopeResolver;

    public KernelWebConfig(DevScopeArgumentResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(scopeResolver);
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${coop-erp.web.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Location", DevScopeArgumentResolver.HEADER_CORRELATION));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", config);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
