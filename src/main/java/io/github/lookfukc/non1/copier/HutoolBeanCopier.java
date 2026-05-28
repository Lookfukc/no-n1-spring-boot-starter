package io.github.lookfukc.non1.copier;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Hutool BeanUtil 属性复制器适配器
 * <p>
 * 通过反射调用 Hutool 的 {@code cn.hutool.core.bean.BeanUtil.copyProperties} 方法。
 * 需要在项目中引入 hutool 依赖：
 * <pre>{@code
 * <dependency>
 *     <groupId>cn.hutool</groupId>
 *     <artifactId>hutool-all</artifactId>
 *     <version>5.x.x</version>
 * </dependency>
 * }</pre>
 * <p>
 * 使用示例：
 * <pre>{@code
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class, HutoolBeanCopier.of())
 *     .withRelation(...)
 *     .build();
 * }</pre>
 * <p>
 * 注意：本适配器通过反射在运行时检测 Hutool 是否可用，编译时不需要 hutool 依赖。
 *
 * @param <S> 源对象类型
 * @param <T> 目标对象类型
 * @author lookfukc
 */
public final class HutoolBeanCopier<S, T> implements BeanCopier<S, T> {

    private static final boolean HUTOOL_AVAILABLE;
    private static final Method COPY_PROPERTIES_METHOD;
    private static final RuntimeException INIT_ERROR;

    static {
        boolean available = false;
        Method method = null;
        RuntimeException error = null;

        try {
            // 检测 Hutool 是否可用
            Class<?> beanUtilClass = Class.forName("cn.hutool.core.bean.BeanUtil");
            method = beanUtilClass.getMethod("copyProperties", Object.class, Object.class);
            available = true;
        } catch (ClassNotFoundException e) {
            error = new IllegalStateException(
                "Hutool 不可用。如需使用 HutoolBeanCopier，请添加依赖：\n" +
                "<dependency>\n" +
                "    <groupId>cn.hutool</groupId>\n" +
                "    <artifactId>hutool-all</artifactId>\n" +
                "    <version>5.x.x</version>\n" +
                "</dependency>",
                e
            );
        } catch (NoSuchMethodException e) {
            error = new RuntimeException("Hutool BeanUtil.copyProperties 方法不存在", e);
        }

        HUTOOL_AVAILABLE = available;
        COPY_PROPERTIES_METHOD = method;
        INIT_ERROR = error;
    }

    private HutoolBeanCopier() {
        if (!HUTOOL_AVAILABLE) {
            throw INIT_ERROR;
        }
    }

    /**
     * 创建 HutoolBeanCopier 实例
     *
     * @param <S> 源对象类型
     * @param <T> 目标对象类型
     * @return HutoolBeanCopier 实例
     * @throws IllegalStateException 如果 Hutool 不可用
     */
    public static <S, T> HutoolBeanCopier<S, T> of() {
        return new HutoolBeanCopier<>();
    }

    /**
     * 检查 Hutool 是否在类路径中可用
     *
     * @return true 如果 Hutool 可用
     */
    public static boolean isAvailable() {
        return HUTOOL_AVAILABLE;
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

        try {
            COPY_PROPERTIES_METHOD.invoke(null, source, target);
        } catch (Exception e) {
            throw new RuntimeException("Hutool BeanUtil 属性复制失败: " + e.getMessage(), e);
        }

        return target;
    }
}
