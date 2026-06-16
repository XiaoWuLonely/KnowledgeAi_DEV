package com.knowflow.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性（secret 密钥、过期时间分钟数），绑定 knowflow.jwt.*
 * 用于配置 JWT 相关的属性，如密钥、过期时间等
 */

@ConfigurationProperties(prefix = "knowflow.jwt")
public class JwtProperties {

    private String secret;
    private long expiresInMinutes;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpiresInMinutes() {
        return expiresInMinutes;
    }

    public void setExpiresInMinutes(long expiresInMinutes) {
        this.expiresInMinutes = expiresInMinutes;
    }
}
