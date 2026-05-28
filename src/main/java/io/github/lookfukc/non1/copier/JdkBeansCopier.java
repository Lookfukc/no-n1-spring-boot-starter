package io.github.lookfukc.non1.copier;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * JDK 内省机制属性复制器适配器
 * <p>
 * 使用 JDK 自带的 {@link java.beans.Introspector} 和 {@link java.beans.PropertyDescriptor}
 * 进行属性复制。通过 getter/setter 方法复制属性，无需额外依赖。
 * <p>
 * 使用示例：
 * <pre>{@code
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class, JdkBeansCopier.of())
 *     .withRelation(...)
 *     .build();
 * }</pre>
 *
 * @param <S> 源对象类型
 * @param <T> 目标对象类型
 * @author lookfukc
 */
public final class JdkBeansCopier<S, T> implements BeanCopier<S, T> {

    private static final JdkBeansCopier<?, ?> INSTANCE = new JdkBeansCopier<>();

    private JdkBeansCopier() {
    }

    /**
     * 获取单例实例
     *
     * @param <S> 源对象类型
     * @param <T> 目标对象类型
     * @return JdkBeansCopier 实例
     */
    @SuppressWarnings("unchecked")
    public static <S, T> JdkBeansCopier<S, T> of() {
        return (JdkBeansCopier<S, T>) INSTANCE;
    }

    @Override
    public T copy(S source, Supplier<T> targetSupplier) {
        if (source == null) {
            return null;
        }

        T target = targetSupplier.get();
        if (target == null) {
            return null;
        }

        Class<?> sourceClass = source.getClass();
        Class<?> targetClass = target.getClass();

        try {
            BeanInfo sourceBeanInfo = Introspector.getBeanInfo(sourceClass);
            BeanInfo targetBeanInfo = Introspector.getBeanInfo(targetClass);

            PropertyDescriptor[] sourcePds = sourceBeanInfo.getPropertyDescriptors();
            PropertyDescriptor[] targetPds = targetBeanInfo.getPropertyDescriptors();

            // 构建目标属性名到 PropertyDescriptor 的映射
            java.util.Map<String, PropertyDescriptor> targetPdMap = new java.util.HashMap<>();
            for (PropertyDescriptor pd : targetPds) {
                if (pd.getWriteMethod() != null) {
                    targetPdMap.put(pd.getName(), pd);
                }
            }

            // 遍历源对象属性，复制到目标对象
            for (PropertyDescriptor sourcePd : sourcePds) {
                if (sourcePd.getReadMethod() == null) {
                    continue;
                }

                PropertyDescriptor targetPd = targetPdMap.get(sourcePd.getName());
                if (targetPd == null || targetPd.getWriteMethod() == null) {
                    continue;
                }

                // 检查类型是否兼容
                if (!targetPd.getPropertyType().isAssignableFrom(sourcePd.getPropertyType())) {
                    continue;
                }

                try {
                    Method readMethod = sourcePd.getReadMethod();
                    Method writeMethod = targetPd.getWriteMethod();

                    Object value = readMethod.invoke(source);
                    if (value != null) {
                        writeMethod.invoke(target, value);
                    }
                } catch (Exception e) {
                    // 忽略复制失败的属性
                }
            }
        } catch (IntrospectionException e) {
            throw new RuntimeException("JDK 内省属性复制失败: " + e.getMessage(), e);
        }

        return target;
    }
}
