package com.talleres360.bff;

import com.talleres360.bff.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** El claim "roles" de Entra ID tiene que llegar a Spring como ROLE_*. */
class RolesClaimConverterTest {

	@Test
	void mapeaScopeYAppRoles() {
		Jwt jwt = Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.claim("scp", "access_as_user")
				.claim("roles", List.of("Operador"))
				.build();

		List<String> authorities = new SecurityConfig().jwtAuthenticationConverter()
				.convert(jwt)
				.getAuthorities()
				.stream()
				.map(GrantedAuthority::getAuthority)
				.collect(Collectors.toList());

		assertThat(authorities).containsExactlyInAnyOrder("SCOPE_access_as_user", "ROLE_Operador");
	}
}
