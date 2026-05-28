package io.github.lookfukc.non1.copier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 默认 Bean 属性复制器实现
 * <p>
 * 使用 Java 原生反射进行属性复制，通过字段缓存提升性能。
 * 复制同名同类型字段，自动忽略 static 和 final 字段。
 * <p>
 * 性能特点：
 * <ul>
 *   <li>首次复制：需要反射获取字段信息，较慢</li>
 *   <li>后续复制：使用缓存的字段信息，性能显著提升</li>
 *   <li>内存占用：每个类缓存一次字段信息</li>
 * </ul>
 *
 * @author lookfukc
 */
public enum DefaultBeanCopier implements BeanCopier<Object, Object> {

    /**
     * 单例实例
     */
    INSTANCE;

    /**
     * 字段缓存：Class → 字段列表
     */
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 原始类型与包装类型的映射关系
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = Map.of(
            byte.class, Byte.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            char.class, Character.class,
            boolean.class, Boolean.class
    );

    @Override
    public Object copy(Object source, java.util.function.Supplier<Object> targetSupplier) {
        if (source == null) {
            return null;
        }

        Object target = targetSupplier.get();
        if (target == null) {
            return null;
        }

        Class<?> sourceClass = source.getClass();
        Class<?> targetClass = target.getClass();

        List<Field> sourceFields = getFields(sourceClass);
        List<Field> targetFields = getFields(targetClass);

        // 构建目标字段名到字段的映射
        Map<String, Field> targetFieldMap = targetFields.stream()
                .collect(Collectors.toMap(Field::getName, f -> f, (a, b) -> a));

        // 复制同名同类型字段
        for (Field sourceField : sourceFields) {
            Field targetField = targetFieldMap.get(sourceField.getName());
            if (targetField == null) {
                continue;
            }

            if (isTypeCompatible(sourceField.getType(), targetField.getType())) {
                copyFieldValue(source, target, sourceField, targetField);
            }
        }

        return target;
    }

    /**
     * 获取类的所有可复制字段（带缓存）
     *
     * @param clazz 目标类
     * @return 字段列表（已过滤 static 和 final 字段）
     */
    private List<Field> getFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> fields = getAllFields(c);
            // 过滤掉 static 和 final 字段
            return fields.stream()
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .filter(f -> !Modifier.isFinal(f.getModifiers()))
                    .toList();
        });
    }

    /**
     * 递归获取类及其父类的所有字段
     *
     * @param clazz 目标类
     * @return 所有声明的字段
     */
    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 判断源字段类型是否可以赋值给目标字段类型
     *
     * @param sourceType 源字段类型
     * @param targetType 目标字段类型
     * @return 类型是否兼容
     */
    private boolean isTypeCompatible(Class<?> sourceType, Class<?> targetType) {
        if (sourceType.equals(targetType)) {
            return true;
        }

        // 处理原始类型和包装类型的兼容性
        Class<?> sourceWrapper = PRIMITIVE_WRAPPER_MAP.getOrDefault(sourceType, sourceType);
        Class<?> targetWrapper = PRIMITIVE_WRAPPER_MAP.getOrDefault(targetType, targetType);

        return sourceWrapper.equals(targetWrapper);
    }

    /**
     * 复制字段值
     *
     * @param source       源对象
     * @param target       目标对象
     * @param sourceField  源字段
     * @param targetField  目标字段
     */
    private void copyFieldValue(Object source, Object target, Field sourceField, Field targetField) {
        try {
            sourceField.setAccessible(true);
            targetField.setAccessible(true);

            Object value = sourceField.get(source);
            if (value != null) {
                targetField.set(target, value);
            }
        } catch (IllegalAccessException e) {
            // 忽略无法访问的字段
        }
    }
}
