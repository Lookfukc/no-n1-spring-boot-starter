package io.github.lookfukc.non1.copier;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * MapStruct 属性复制器适配器
 * <p>
 * 将 MapStruct 生成的转换函数适配为 {@link BeanCopier} 接口。
 * MapStruct 在编译期生成转换代码，性能接近手写代码，是追求高性能的最佳选择。
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 定义 MapStruct Mapper
 * @Mapper(componentModel = "spring")
 * public interface UserMapper {
 *     UserVO toVO(User user);
 * }
 *
 * // 使用 MapStructBeanCopier
 * UserMapper mapper = Mappers.getMapper(UserMapper.class);
 * List<UserVO> result = RelationAssembler.from(users, UserVO.class, mapper::toVO)
 *     .withRelation(...)
 *     .build();
 * }</pre>
 *
 * @param <S> 源对象类型
 * @param <T> 目标对象类型
 * @author lookfukc
 */
public final class MapStructBeanCopier<S, T> implements BeanCopier<S, T> {

    private final Function<S, T> converter;

    private MapStructBeanCopier(Function<S, T> converter) {
        this.converter = converter;
    }

    /**
     * 创建 MapStructBeanCopier 实例
     *
     * @param converter MapStruct 转换函数，通常是 {@code mapper::toVO}
     * @param <S>       源对象类型
     * @param <T>       目标对象类型
     * @return MapStructBeanCopier 实例
     */
    public static <S, T> MapStructBeanCopier<S, T> of(Function<S, T> converter) {
        return new MapStructBeanCopier<>(converter);
    }

    @Override
    public T copy(S source, Supplier<T> targetSupplier) {
        // MapStruct 已经创建新对象，忽略 targetSupplier
        return converter.apply(source);
    }
}
