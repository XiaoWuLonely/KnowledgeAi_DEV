package com.knowflow.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aliyun OSS configuration.
 * 用于配置 OSS 相关的属性，如 endpoint、bucket、accessKeyId、accessKeySecret、avatarPrefix 等
 */
@Data
@ConfigurationProperties(prefix = "knowflow.oss")
public class OssProperties {
    private String endpoint;
    private String bucket;
    private String accessKeyId;
    private String accessKeySecret;
    private String avatarPrefix = "knowflow/avatars";
}
