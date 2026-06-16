package com.knowflow.backend.auth.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.knowflow.backend.config.JwtProperties;
import com.knowflow.backend.auth.entity.User;

@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    /**
     * @Desc 生成JWT令牌
     * @param user
     * @return
     */
    public String generateToken(User user) {
        Instant now = Instant.now(); // 当前时间
        Instant expiresAt = now.plus(jwtProperties.getExpiresInMinutes(), ChronoUnit.MINUTES); // 过期时间

        /**
         * 构建JWT令牌的声明集
         */
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("knowflow-backend")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("username", user.getUsername())
                .claim("roles", List.of(user.getRole()))
                .build();

        /**
         * 构建JWT令牌的头
         * @param header 算法名称为HS256，密钥为JWT属性中的密钥
         */
        JwsHeader header = JwsHeader.with(() -> "HS256").build();

        /**
         * 编码JWT令牌
         * @param tokenValue 编码后的JWT令牌
         */
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
