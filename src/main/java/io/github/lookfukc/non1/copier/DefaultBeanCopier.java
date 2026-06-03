package io.github.lookfukc.non1.copier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 默认 Bean 属性复制器实现
 * <p>
 * 使用 Java 原生反射进行属性复制，通过字段缓存和字段对缓存提升性能。
 * 复制同名同类型字段，自动忽略 static 和 final 字段。
 * <p>
 * 性能特点：
 * <ul>
 *   <li>首次复制：需要反射获取字段信息并构建字段对，较慢</li>
 *   <li>后续复制：使用缓存的字段对直接赋值，性能显著提升</li>
 *   <li>内存占用：每个类对缓存一次字段对信息</li>
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
     * 字段缓存：Class → 字段数组（已过滤 static 和 final 字段）
     */
    private static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();

    /**
     * 字段对缓存：sourceClassName#targetClassName → 匹配的字段对数组
     */
    private static final Map<String, FieldPair[]> PAIR_CACHE = new ConcurrentHashMap<>();

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
    public Object copy(Object source, Supplier<Object> targetSupplier) {
        if (source == null) {
            return null;
        }

        Object target = targetSupplier.get();
        if (target == null) {
            return null;
        }

        // 通过缓存键获取字段对，避免每次都做字段匹配
        String cacheKey = source.getClass().getName() + '#' + target.getClass().getName();
        FieldPair[] pairs = PAIR_CACHE.computeIfAbsent(cacheKey,
                k -> buildFieldPairs(source.getClass(), target.getClass()));

        for (int i = 0, len = pairs.length; i < len; i++) {
            FieldPair pair = pairs[i];
            try {
                Object value = pair.sourceField.get(source);
                if (value != null) {
                    pair.targetField.set(target, value);
                }
            } catch (IllegalAccessException e) {
                // 忽略无法访问的字段
            }
        }

        return target;
    }

    /**
     * 构建源类与目标类之间的字段对
     *
     * @param sourceClass 源类
     * @param targetClass 目标类
     * @return 匹配的字段对数组
     */
    private static FieldPair[] buildFieldPairs(Class<?> sourceClass, Class<?> targetClass) {
        Field[] sourceFields = getCachedFields(sourceClass);
        Field[] targetFields = getCachedFields(targetClass);

        // 构建目标字段名到字段的映射
        Map<String, Field> targetFieldMap = new HashMap<>(targetFields.length * 4 / 3 + 1);
        for (Field f : targetFields) {
            targetFieldMap.put(f.getName(), f);
        }

        List<FieldPair> pairs = new ArrayList<>(Math.min(sourceFields.length, targetFields.length));
        for (Field sourceField : sourceFields) {
            Field targetField = targetFieldMap.get(sourceField.getName());
            if (targetField != null && isTypeCompatible(sourceField.getType(), targetField.getType())) {
                // 提前设置 accessible，避免在热路径中重复调用
                sourceField.setAccessible(true);
                targetField.setAccessible(true);
                pairs.add(new FieldPair(sourceField, targetField));
            }
        }

        return pairs.toArray(new FieldPair[0]);
    }

    /**
     * 获取类的所有可复制字段（带缓存）
     *
     * @param clazz 目标类
     * @return 字段数组（已过滤 static 和 final 字段）
     */
    private static Field[] getCachedFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> fields = new ArrayList<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (!Modifier.isStatic(mod) && !Modifier.isFinal(mod)) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
                current = current.getSuperclass();
            }
            return fields.toArray(new Field[0]);
        });
    }

    /**
     * 判断源字段类型是否可以赋值给目标字段类型
     *
     * @param sourceType 源字段类型
     * @param targetType 目标字段类型
     * @return 类型是否兼容
     */
    private static boolean isTypeCompatible(Class<?> sourceType, Class<?> targetType) {
        if (sourceType.equals(targetType)) {
            return true;
        }
        // 处理原始类型和包装类型的兼容性
        Class<?> sourceWrapper = PRIMITIVE_WRAPPER_MAP.getOrDefault(sourceType, sourceType);
        Class<?> targetWrapper = PRIMITIVE_WRAPPER_MAP.getOrDefault(targetType, targetType);
        return sourceWrapper.equals(targetWrapper);
    }

    /**
     * 字段对，缓存源字段与目标字段的映射关系
     */
    private static final class FieldPair {
        final Field sourceField;
        final Field targetField;

        FieldPair(Field sourceField, Field targetField) {
            this.sourceField = sourceField;
            this.targetField = targetField;
        }
    }
}
