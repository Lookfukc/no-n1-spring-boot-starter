package io.github.lookfukc.non1.copier;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

import java.util.function.Supplier;

/**
 * Spring BeanUtils 属性复制器适配器
 * <p>
 * 使用 Spring Framework 提供的 {@link org.springframework.beans.BeanUtils#copyProperties(Object, Object)}
 * 进行属性复制。适用于 Spring Boot 应用，无需额外依赖。
 * <p>
 * 使用示例：
 * <pre>{@code
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class, SpringBeanUtilsCopier.of())
 *     .withRelation(...)
 *     .build();
 * }</pre>
 *
 * @param <S> 源对象类型
 * @param <T> 目标对象类型
 * @author lookfukc
 */
public final class SpringBeanUtilsCopier<S, T> implements BeanCopier<S, T> {

    private static final SpringBeanUtilsCopier<?, ?> INSTANCE = new SpringBeanUtilsCopier<>();

    private SpringBeanUtilsCopier() {
    }

    /**
     * 获取单例实例
     *
     * @param <S> 源对象类型
     * @param <T> 目标对象类型
     * @return SpringBeanUtilsCopier 实例
     */
    @SuppressWarnings("unchecked")
    public static <S, T> SpringBeanUtilsCopier<S, T> of() {
        return (SpringBeanUtilsCopier<S, T>) INSTANCE;
    }

    @Override
    public T copy(@Nullable S source, Supplier<T> targetSupplier) {
        if (source == null) {
            return null;
        }

        T target = targetSupplier.get();
        if (target == null) {
            return null;
        }

        try {
            BeanUtils.copyProperties(source, target);
        } catch (BeansException e) {
            throw new RuntimeException("Spring BeanUtils 属性复制失败: " + e.getMessage(), e);
        }

        return target;
    }
}
