package com.company.orderapi.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * PR #26 - converts a validated JWT into an authenticated token whose
 * authorities come from the OAuth2 {@code scope} claim (order_read,
 * order_write, ...). Scopes are namespaced {@code SCOPE_<scope>} so
 * {@code @PreAuthorize("hasAuthority('SCOPE_order_write')")} works directly.
 */
public class JwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String scopeClaim = jwt.getClaimAsString("scope");
        var authorities = Arrays.stream(scopeClaim == null ? new String[0]
                        : scopeClaim.split(" "))
                .filter(s -> !s.isBlank())
                .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
                .collect(Collectors.toSet());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
