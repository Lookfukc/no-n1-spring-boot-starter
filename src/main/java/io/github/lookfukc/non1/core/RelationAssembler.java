package io.github.lookfukc.non1.core;

import io.github.lookfukc.non1.copier.BeanCopier;
import io.github.lookfukc.non1.copier.DefaultBeanCopier;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关联对象组装器
 * <p>
 * 通过批量查询将关联对象组装到值对象（VO）中，用于解决 N+1 查询问题。
 * 将原本 O(n) 的数据库查询复杂度降低到 O(1)，显著提升性能。
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 使用默认属性复制器（反射 + 缓存）
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class)
 *     .withRelation(
 *         Order::getUserId,
 *         ids -> userRepository.findAllById(ids),
 *         User::getId,
 *         OrderVO::setUser
 *     )
 *     .build();
 *
 * // 使用 MapStruct 转换器
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
 *     .withRelation(
 *         Order::getUserId,
 *         ids -> userRepository.findAllById(ids),
 *         User::getId,
 *         userMapper::toVO,
 *         OrderVO::setUser
 *     )
 *     .build();
 *
 * // 启用并行查询
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class)
 *     .parallel()
 *     .withRelation(
 *         Order::getUserId,
 *         ids -> userRepository.findAllById(ids),
 *         User::getId,
 *         userMapper::toVO,
 *         OrderVO::setUser
 *     )
 *     .withRelation(
 *         Order::getProductId,
 *         ids -> productRepository.findAllById(ids),
 *         Product::getId,
 *         productMapper::toVO,
 *         OrderVO::setProduct
 *     )
 *     .build();
 * }</pre>
 *
 * @param <S> 源实体类型
 * @param <T> 目标 VO 类型
 * @author lookfukc
 */
public class RelationAssembler<S, T> {

    /**
     * 创建构建器，使用默认属性复制器
     * <p>
     * 默认使用 {@link DefaultBeanCopier}，基于反射和字段缓存实现属性复制，
     * 无需额外依赖。
     *
     * @param sourceList 源实体列表
     * @param voClass    目标 VO 类型
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    @SuppressWarnings("unchecked")
    public static <S, T> Builder<S, T> from(List<S> sourceList, Class<T> voClass) {
        return new Builder<>(sourceList, voClass, (BeanCopier<S, T>) DefaultBeanCopier.INSTANCE);
    }

    /**
     * 创建构建器，使用自定义转换函数
     * <p>
     * 适用于使用 MapStruct 或自定义 Lambda 表达式的场景。
     *
     * @param sourceList 源实体列表
     * @param voClass    目标 VO 类型
     * @param converter  转换函数，通常是 MapStruct 生成的映射方法
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    public static <S, T> Builder<S, T> from(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
        return new Builder<>(sourceList, voClass, converter);
    }

    /**
     * 创建构建器，使用自定义属性复制器
     * <p>
     * 当需要完全控制属性复制逻辑时使用此方法。
     * 内置的属性复制器包括：
     * <ul>
     *   <li>{@link io.github.lookfukc.non1.copier.SpringBeanUtilsCopier} - 使用 Spring BeanUtils</li>
     *   <li>{@link io.github.lookfukc.non1.copier.HutoolBeanCopier} - 使用 Hutool BeanUtil</li>
     *   <li>{@link io.github.lookfukc.non1.copier.JdkBeansCopier} - 使用 JDK 内省机制</li>
     *   <li>{@link io.github.lookfukc.non1.copier.MapStructBeanCopier} - 适配 MapStruct 转换函数</li>
     * </ul>
     *
     * @param sourceList 源实体列表
     * @param voClass    目标 VO 类型
     * @param copier     属性复制器实现
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    public static <S, T> Builder<S, T> from(List<S> sourceList, Class<T> voClass, BeanCopier<S, T> copier) {
        return new Builder<>(sourceList, voClass, copier);
    }

    /**
     * 链式构建器，用于配置关联组装操作
     *
     * @param <S> 源实体类型
     * @param <T> 目标 VO 类型
     */
    public static class Builder<S, T> {
        private final List<S> sourceList;
        private final Class<T> voClass;
        private final BeanCopier<S, T> copier;
        private final List<BaseRelationConfig<?, ?>> relations;
        private boolean parallel = false;
        private Executor executor = ForkJoinPool.commonPool();

        private Builder(List<S> sourceList, Class<T> voClass, BeanCopier<S, T> copier) {
            this.sourceList = sourceList;
            this.voClass = voClass;
            this.copier = copier;
            this.relations = new ArrayList<>();
        }

        /**
         * 适配 Function 到 BeanCopier 接口的构造器
         */
        private Builder(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
            this.sourceList = sourceList;
            this.voClass = voClass;
            this.copier = (source, targetSupplier) -> converter.apply(source);
            this.relations = new ArrayList<>();
        }

        /**
         * 启用并行查询执行，使用默认的 ForkJoinPool
         * <p>
         * 当需要查询多个独立的关联对象时，并行执行可以显著减少总查询时间。
         *
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> parallel() {
            this.parallel = true;
            return this;
        }

        /**
         * 启用并行查询执行，使用自定义的执行器
         *
         * @param executor 用于并行查询的线程池执行器
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> parallel(Executor executor) {
            this.parallel = true;
            this.executor = executor;
            return this;
        }

        /**
         * 添加关联对象配置（无类型转换）
         * <p>
         * 当查询的关联对象类型与 VO 字段类型一致时使用此方法。
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接收 ID 集合返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param voSetter         将关联对象设置到 VO 的函数
         * @param <I>              ID 类型（通常为 Long）
         * @param <R>              关联对象类型
         * @return 当前构建器，用于链式调用
         */
        public <I, R> Builder<S, T> withRelation(
                Function<S, I> extractor,
                Function<Set<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                BiConsumer<T, R> voSetter) {
            relations.add(new RelationConfig<>(extractor, queryFunction, relationIdGetter, voSetter));
            return this;
        }

        /**
         * 添加关联对象配置（带类型转换）
         * <p>
         * 当查询的关联对象类型需要转换为 VO 字段类型时使用此方法。
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接收 ID 集合返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数，将关联对象类型转换为 VO 字段类型
         * @param voSetter         将转换后的关联对象设置到 VO 的函数
         * @param <I>              ID 类型（通常为 Long）
         * @param <R>              查询返回的关联对象类型
         * @param <V>              转换后的关联对象类型
         * @return 当前构建器，用于链式调用
         */
        public <I, R, V> Builder<S, T> withRelation(
                Function<S, I> extractor,
                Function<Set<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                Function<R, V> converter,
                BiConsumer<T, V> voSetter) {
            relations.add(new RelationConfigWithConverter<>(extractor, queryFunction, relationIdGetter, converter, voSetter));
            return this;
        }

        /**
         * 添加关联对象配置（基于 List 的查询，无类型转换）
         * <p>
         * 当批量查询方法接受 List 而非 Set 时使用此方法。
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接受 ID 列表返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param voSetter         将关联对象设置到 VO 的函数
         * @param <I>              ID 类型（通常为 Long）
         * @param <R>              关联对象类型
         * @return 当前构建器，用于链式调用
         */
        public <I, R> Builder<S, T> withRelationList(
                Function<S, I> extractor,
                Function<List<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                BiConsumer<T, R> voSetter) {
            relations.add(new RelationConfigWithList<>(extractor, queryFunction, relationIdGetter, voSetter));
            return this;
        }

        /**
         * 添加关联对象配置（基于 List 的查询，带类型转换）
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接受 ID 列表返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数，将关联对象类型转换为 VO 字段类型
         * @param voSetter         将转换后的关联对象设置到 VO 的函数
         * @param <I>              ID 类型（通常为 Long）
         * @param <R>              查询返回的关联对象类型
         * @param <V>              转换后的关联对象类型
         * @return 当前构建器，用于链式调用
         */
        public <I, R, V> Builder<S, T> withRelationList(
                Function<S, I> extractor,
                Function<List<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                Function<R, V> converter,
                BiConsumer<T, V> voSetter) {
            relations.add(new RelationConfigWithListAndConverter<>(extractor, queryFunction, relationIdGetter, converter, voSetter));
            return this;
        }

        /**
         * 构建 VO 列表
         * <p>
         * 执行流程：
         * <ol>
         *   <li>执行所有配置的关联对象批量查询（并行或串行）</li>
         *   <li>使用配置的属性复制器将源实体转换为 VO</li>
         *   <li>将查询结果中的关联对象设置到对应的 VO 中</li>
         * </ol>
         *
         * @return 组装完成后的 VO 列表
         * @throws RuntimeException 如果 VO 实例化或组装失败
         */
        public List<T> build() {
            if (CollectionUtils.isEmpty(sourceList)) {
                return new ArrayList<>();
            }

            List<Map<?, ?>> maps;
            if (parallel && relations.size() > 1) {
                maps = queryRelationsInParallel();
            } else {
                maps = queryRelationsSequentially();
            }

            List<T> voList = new ArrayList<>(sourceList.size());
            for (S source : sourceList) {
                try {
                    T vo = copier.copy(source, () -> {
                        try {
                            return voClass.getDeclaredConstructor().newInstance();
                        } catch (Exception e) {
                            throw new RuntimeException("创建 VO 实例失败: " + e.getMessage(), e);
                        }
                    });

                    if (vo != null) {
                        for (int i = 0; i < relations.size(); i++) {
                            BaseRelationConfig<?, ?> relation = relations.get(i);
                            relation.setRelation(vo, maps.get(i), source);
                        }
                        voList.add(vo);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("组装 VO 失败: " + e.getMessage(), e);
                }
            }
            return voList;
        }

        /**
         * 串行执行所有关联对象查询
         */
        private List<Map<?, ?>> queryRelationsSequentially() {
            List<Map<?, ?>> maps = new ArrayList<>(relations.size());
            for (BaseRelationConfig<?, ?> relation : relations) {
                maps.add(relation.queryAndBuildMap(sourceList));
            }
            return maps;
        }

        /**
         * 使用 CompletableFuture 并行执行所有关联对象查询
         */
        @SuppressWarnings("unchecked")
        private List<Map<?, ?>> queryRelationsInParallel() {
            List<CompletableFuture<Map<?, ?>>> futures = new ArrayList<>(relations.size());

            for (BaseRelationConfig<?, ?> relation : relations) {
                CompletableFuture<Map<?, ?>> future = CompletableFuture.supplyAsync(
                        () -> relation.queryAndBuildMap(sourceList),
                        executor
                );
                futures.add(future);
            }

            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            try {
                allOf.join();
                List<Map<?, ?>> maps = new ArrayList<>(relations.size());
                for (CompletableFuture<Map<?, ?>> future : futures) {
                    maps.add(future.get());
                }
                return maps;
            } catch (Exception e) {
                throw new RuntimeException("并行查询执行失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 关联对象配置基类
     *
     * @param <I> ID 类型
     * @param <R> 关联对象类型
     */
    @SuppressWarnings("rawtypes")
    private abstract static class BaseRelationConfig<I, R> {
        protected final Function extractor;
        protected final Function queryFunction;
        protected final Function relationIdGetter;

        protected BaseRelationConfig(
                Function extractor,
                Function queryFunction,
                Function relationIdGetter) {
            this.extractor = extractor;
            this.queryFunction = queryFunction;
            this.relationIdGetter = relationIdGetter;
        }

        /**
         * 从源列表提取 ID，执行批量查询，构建 ID 到关联对象的映射
         */
        @SuppressWarnings("unchecked")
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList) {
            Set<I> ids = (Set<I>) sourceList.stream()
                    .map(extractor)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (ids.isEmpty()) {
                return new HashMap<>();
            }

            List<R> relations = (List<R>) queryFunction.apply(ids);
            return (Map<I, ?>) relations.stream()
                    .collect(Collectors.toMap(relationIdGetter, r -> r, (a, b) -> a));
        }

        protected abstract void setRelation(Object vo, Map<?, ?> relationMap, Object source);
    }

    /**
     * 无类型转换的关联对象配置
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfig<I, R> extends BaseRelationConfig<I, R> {
        private final BiConsumer voSetter;

        private RelationConfig(
                Function extractor,
                Function queryFunction,
                Function relationIdGetter,
                BiConsumer voSetter) {
            super(extractor, queryFunction, relationIdGetter);
            this.voSetter = voSetter;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            I id = (I) extractor.apply(source);
            Object relation = relationMap.get(id);
            ((BiConsumer) voSetter).accept(vo, relation);
        }
    }

    /**
     * 带类型转换的关联对象配置
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithConverter<I, R, V> extends BaseRelationConfig<I, R> {
        private final Function<R, V> converter;
        private final BiConsumer voSetter;

        private RelationConfigWithConverter(
                Function extractor,
                Function queryFunction,
                Function relationIdGetter,
                Function<R, V> converter,
                BiConsumer voSetter) {
            super(extractor, queryFunction, relationIdGetter);
            this.converter = converter;
            this.voSetter = voSetter;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            I id = (I) extractor.apply(source);
            R relation = (R) relationMap.get(id);
            if (relation != null) {
                V converted = converter.apply(relation);
                ((BiConsumer) voSetter).accept(vo, converted);
            }
        }
    }

    /**
     * 基于 List 查询的关联对象配置（无类型转换）
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithList<I, R> extends BaseRelationConfig<I, R> {
        private final Function<List<I>, List<R>> queryFunctionWithList;
        private final BiConsumer voSetter;

        private RelationConfigWithList(
                Function extractor,
                Function<List<I>, List<R>> queryFunctionWithList,
                Function relationIdGetter,
                BiConsumer voSetter) {
            super(extractor, null, relationIdGetter);
            this.queryFunctionWithList = queryFunctionWithList;
            this.voSetter = voSetter;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList) {
            List<I> idList = (List<I>) sourceList.stream()
                    .map(extractor)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            if (idList.isEmpty()) {
                return new HashMap<>();
            }

            List<R> relations = queryFunctionWithList.apply(idList);
            return (Map<I, ?>) relations.stream()
                    .collect(Collectors.toMap(relationIdGetter, r -> r, (a, b) -> a));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            I id = (I) extractor.apply(source);
            Object relation = relationMap.get(id);
            ((BiConsumer) voSetter).accept(vo, relation);
        }
    }

    /**
     * 基于 List 查询的关联对象配置（带类型转换）
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithListAndConverter<I, R, V> extends BaseRelationConfig<I, R> {
        private final Function<List<I>, List<R>> queryFunctionWithList;
        private final Function<R, V> converter;
        private final BiConsumer voSetter;

        private RelationConfigWithListAndConverter(
                Function extractor,
                Function<List<I>, List<R>> queryFunctionWithList,
                Function relationIdGetter,
                Function<R, V> converter,
                BiConsumer voSetter) {
            super(extractor, null, relationIdGetter);
            this.queryFunctionWithList = queryFunctionWithList;
            this.converter = converter;
            this.voSetter = voSetter;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList) {
            List<I> idList = (List<I>) sourceList.stream()
                    .map(extractor)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            if (idList.isEmpty()) {
                return new HashMap<>();
            }

            List<R> relations = queryFunctionWithList.apply(idList);
            return (Map<I, ?>) relations.stream()
                    .collect(Collectors.toMap(relationIdGetter, r -> r, (a, b) -> a));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            I id = (I) extractor.apply(source);
            R relation = (R) relationMap.get(id);
            if (relation != null) {
                V converted = converter.apply(relation);
                ((BiConsumer) voSetter).accept(vo, converted);
            }
        }
    }
}
