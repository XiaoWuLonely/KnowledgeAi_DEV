package com.knowflow.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;


/**
 * AI 配置类
 * 用于启用 AI 配置属性和模型安全配置属性
 * AI 模型客户端配置，读取 knowflow.ai.*，创建 RestClient Bean
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, ModelSecurityProperties.class, OssProperties.class})
public class AiConfig {
}
