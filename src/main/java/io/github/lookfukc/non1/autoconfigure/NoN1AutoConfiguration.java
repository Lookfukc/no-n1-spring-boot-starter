package io.github.lookfukc.non1.autoconfigure;

import org.springframework.context.annotation.Configuration;

/**
 * NoN1 Spring Boot 自动配置类
 * <p>
 * 用于 Spring Boot 自动识别 Starter，无实际配置逻辑。
 * <p>
 * {@link io.github.lookfukc.non1.core.RelationAssembler} 提供静态 API，
 * 支持通过链式调用配置行为（如启用并行查询、自定义线程池等），
 * 无需额外的 Spring 配置。
 *
 * @author lookfukc
 */
@Configuration
public class NoN1AutoConfiguration {
    // 当前为空配置，RelationAssembler 通过静态 API 使用
}
