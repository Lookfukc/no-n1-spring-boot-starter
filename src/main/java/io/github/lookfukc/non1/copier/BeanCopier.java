package io.github.lookfukc.non1.copier;

/**
 * Bean 属性复制器接口
 * <p>
 * 定义了将源对象属性复制到目标对象的规范。不同实现提供了不同的
 * 复制策略，在性能特性和依赖方面各有差异。内置实现包括：
 * <ul>
 *   <li>{@link DefaultBeanCopier} - 基于反射的字段复制，带缓存机制</li>
 *   <li>{@link SpringBeanUtilsCopier} - 使用 Spring Framework 的 BeanUtils</li>
 *   <li>{@link HutoolBeanCopier} - 使用 Hutool 的 BeanUtil</li>
 *   <li>{@link JdkBeansCopier} - 使用 JDK 内省机制（Introspector）</li>
 *   <li>{@link MapStructBeanCopier} - 适配 MapStruct 转换函数</li>
 * </ul>
 *
 * @param <S> 源对象类型
 * @param <T> 目标对象类型
 */
@FunctionalInterface
public interface BeanCopier<S, T> {

    /**
     * 将源对象的属性复制到目标对象
     *
     * @param source          源对象
     * @param targetSupplier 目标对象供应函数，某些实现可能会忽略此参数
     * @return 填充属性后的目标对象，若源对象为 null 则返回 null
     */
    T copy(S source, java.util.function.Supplier<T> targetSupplier);
}
