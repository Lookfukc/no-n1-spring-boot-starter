package io.github.lookfukc.non1.core;

import io.github.lookfukc.non1.copier.BeanCopier;
import io.github.lookfukc.non1.copier.DefaultBeanCopier;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 关联对象组装器
 * <p>
 * 通过批量查询将关联对象组装到值对象（VO）中，用于解决 N+1 查询问题。
 * 将原本 O(n) 的数据库查询复杂度降低到 O(1)，显著提升性能。
 * <p>
 * 核心思想：先提取所有关联 ID，通过一次批量查询获取所有关联对象，再通过 Map 映射组装到 VO 中，
 * 避免了逐条查询导致的 N+1 问题。
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
 * // 启用并行查询（多个关联对象同时查询）
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
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
 *
 * // 启用全链路优化（查询并行 + VO 转换并行，适用于大数据量）
 * List<OrderVO> result = RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
 *     .fast()
 *     .withRelation(...)
 *     .build();
 * }</pre>
 *
 * @param <S> 源实体类型
 * @param <T> 目标 VO 类型
 * @author lookfukc
 */
public class RelationAssembler<S, T> {

    /**
     * VO 类构造器缓存，避免每次创建实例时重复反射获取
     */
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    /**
     * Map → Bean 字段缓存：VO Class → 可写入字段数组
     */
    private static final Map<Class<?>, Field[]> MAP_FIELD_CACHE = new ConcurrentHashMap<>();

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
     * @param converter  转换函数，通常是 MapStruct 生成的映射方法，如 {@code orderMapper::toVO}
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    public static <S, T> Builder<S, T> from(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
        return new Builder<>(sourceList, voClass, converter);
    }

    /**
     * 单个对象转换（便捷方法）
     * <p>
     * 内部使用列表转换，返回第一个元素。适用于单个对象的关联组装场景。
     *
     * @param source    源实体，如果为 null 则返回 null
     * @param voClass   目标 VO 类型
     * @param converter 转换函数，通常是 MapStruct 生成的映射方法
     * @param <S>       源实体类型
     * @param <T>       目标 VO 类型
     * @return 转换后的 VO，如果源实体为 null 则返回 null
     */
    public static <S, T> T from(S source, Class<T> voClass, Function<S, T> converter) {
        if (source == null) {
            return null;
        }
        List<T> result = from(Collections.singletonList(source), voClass, converter).build();
        return result.isEmpty() ? null : result.get(0);
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
     * 从 Map 列表创建构建器
     * <p>
     * 适用于 MongoDB Document、动态查询结果等返回 {@link Map} 而非 Java Bean 的场景。
     * 内部通过反射将 Map 中的值按 key 与 VO 字段名匹配写入。
     * <p>
     * 使用示例：
     * <pre>{@code
     * List<Map<String, Object>> mapList = mongoTemplate.find(query, Document.class);
     * List<OrderVO> result = RelationAssembler.fromMaps(mapList, OrderVO.class)
     *     .withRelation(
     *         map -> (Long) map.get("userId"),
     *         ids -> userRepository.findAllById(ids),
     *         User::getId,
     *         userMapper::toVO,
     *         OrderVO::setUser
     *     )
     *     .build();
     * }</pre>
     *
     * @param mapList 源 Map 列表
     * @param voClass 目标 VO 类型
     * @param <T>     目标 VO 类型
     * @return 构建器实例
     */
    public static <T> Builder<Map<String, Object>, T> fromMaps(List<Map<String, Object>> mapList, Class<T> voClass) {
        return new Builder<>(mapList, voClass, createMapConverter(voClass));
    }

    /**
     * 从 Map 列表创建构建器，使用自定义转换函数
     *
     * @param mapList   源 Map 列表
     * @param voClass   目标 VO 类型
     * @param converter 转换函数，将 Map 转换为 VO
     * @param <T>       目标 VO 类型
     * @return 构建器实例
     */
    public static <T> Builder<Map<String, Object>, T> fromMaps(List<Map<String, Object>> mapList, Class<T> voClass,
                                                                 Function<Map<String, Object>, T> converter) {
        return new Builder<>(mapList, voClass, converter);
    }

    /**
     * 创建 Map → VO 的转换函数（带字段缓存）
     */
    private static <T> Function<Map<String, Object>, T> createMapConverter(Class<T> voClass) {
        Constructor<T> ctor = resolveConstructor(voClass);
        Field[] fields = MAP_FIELD_CACHE.computeIfAbsent(voClass, c -> {
            List<Field> list = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) && !Modifier.isFinal(mod)) {
                    f.setAccessible(true);
                    list.add(f);
                }
            }
            return list.toArray(new Field[0]);
        });
        return map -> {
            try {
                T instance = ctor.newInstance();
                for (Field field : fields) {
                    Object value = map.get(field.getName());
                    if (value != null && field.getType().isInstance(value)) {
                        field.set(instance, value);
                    }
                }
                return instance;
            } catch (Exception e) {
                throw new RuntimeException("从 Map 创建 VO 实例失败: " + e.getMessage(), e);
            }
        };
    }

    /**
     * 解析并缓存 VO 类的无参构造器
     *
     * @param clazz VO 类
     * @param <T>   VO 类型
     * @return 缓存的构造器
     * @throws RuntimeException 如果 VO 类没有无参构造函数
     */
    @SuppressWarnings("unchecked")
    private static <T> Constructor<T> resolveConstructor(Class<T> clazz) {
        return (Constructor<T>) CONSTRUCTOR_CACHE.computeIfAbsent(clazz, c -> {
            try {
                Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor;
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("VO 类缺少无参构造函数: " + c.getName(), e);
            }
        });
    }

    /**
     * 创建 VO 实例供应器
     *
     * @param constructor VO 类的无参构造器
     * @param <T>         VO 类型
     * @return VO 实例供应函数
     */
    private static <T> Supplier<T> createVoSupplier(Constructor<T> constructor) {
        return () -> {
            try {
                return constructor.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("创建 VO 实例失败: " + e.getMessage(), e);
            }
        };
    }

    /**
     * 链式构建器，用于配置关联组装操作
     * <p>
 *      * 支持的配置项：
     * <ul>
     *   <li>{@link #parallel()} - 启用查询并行</li>
     *   <li>{@link #fast()} - 启用全链路优化（查询并行 + VO 转换并行）</li>
     *   <li>{@link #withSharedData(String, Object)} - 添加共享数据</li>
     *   <li>{@link #withRelation} - 添加关联对象配置</li>
     * </ul>
     *
     * @param <S> 源实体类型
     * @param <T> 目标 VO 类型
     */
    public static class Builder<S, T> {
        private static final Logger log = LoggerFactory.getLogger(Builder.class);

        /** 源实体列表 */
        private final List<S> sourceList;
        /** 目标 VO 类型 */
        private final Class<T> voClass;
        /** 属性复制器 */
        private final BeanCopier<S, T> copier;
        /** 关联对象配置列表 */
        private final List<BaseRelationConfig<?, ?>> relations;
        /** 共享数据，用于多层嵌套场景传递预加载数据 */
        private final Map<String, Object> sharedData;
        /** VO 实例供应器（带构造器缓存） */
        private final Supplier<T> voSupplier;
        /** 是否启用并行查询 */
        private boolean parallel = false;
        /** 是否启用全链路优化（查询并行 + VO 转换并行） */
        private boolean fast = false;
        /** 并行执行器 */
        private Executor executor = ForkJoinPool.commonPool();
        /** 查询分批大小，0 表示不分批 */
        private int queryBatchSize = 0;
        /** 处理分页大小，0 表示不分页 */
        private int pageSize = 0;

        /**
         * 使用自定义属性复制器创建构建器
         *
         * @param sourceList 源实体列表
         * @param voClass    目标 VO 类型
         * @param copier     属性复制器实现
         */
        private Builder(List<S> sourceList, Class<T> voClass, BeanCopier<S, T> copier) {
            this.sourceList = sourceList;
            this.voClass = voClass;
            this.copier = copier;
            this.relations = new ArrayList<>();
            this.sharedData = new HashMap<>();
            this.voSupplier = createVoSupplier(resolveConstructor(voClass));
        }

        /**
         * 使用自定义转换函数创建构建器
         *
         * @param sourceList 源实体列表
         * @param voClass    目标 VO 类型
         * @param converter  转换函数，如 MapStruct 的映射方法
         */
        private Builder(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
            this.sourceList = sourceList;
            this.voClass = voClass;
            this.copier = (source, targetSupplier) -> converter.apply(source);
            this.relations = new ArrayList<>();
            this.sharedData = new HashMap<>();
            this.voSupplier = createVoSupplier(resolveConstructor(voClass));
        }

        /**
         * 启用并行查询执行，使用默认的 ForkJoinPool
         * <p>
         * 当需要查询多个独立的关联对象时，并行执行可以显著减少总查询时间。
         * 例如 3 个关联对象各耗时 10ms，串行需 30ms，并行仅需约 10ms。
         *
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> parallel() {
            this.parallel = true;
            return this;
        }

        /**
         * 启用并行查询执行，使用自定义的执行器
         * <p>
         * 适用于需要控制线程池大小或使用业务线程池的场景。
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
         * 启用全链路优化模式（查询并行 + VO 转换并行）
         * <p>
         * 同时开启关联查询并行执行和 VO 组装并行处理，适用于大数据量场景。
         * 内部会按 CPU 核心数自动分片，充分利用多核性能。
         * <p>
         * 注意：converter 必须是线程安全的（MapStruct 生成的代码天然线程安全）。
         *
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> fast() {
            this.parallel = true;
            this.fast = true;
            return this;
        }

        /**
         * 启用全链路优化模式，使用自定义执行器
         *
         * @param executor 用于并行查询和 VO 组装的线程池执行器
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> fast(Executor executor) {
            this.parallel = true;
            this.fast = true;
            this.executor = executor;
            return this;
        }

        /**
         * 设置查询分批大小（使用默认值 1000）
         * <p>
         * 当关联 ID 数量超过 batchSize 时，自动拆分为多次查询，避免数据库 IN 子句过长。
         * 例如 10 万个 ID，batchSize=1000 时，会拆分为 100 次查询再合并结果。
         *
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> queryBatchSize() {
            return queryBatchSize(1000);
        }

        /**
         * 设置查询分批大小
         *
         * @param batchSize 每批查询的最大 ID 数量，必须大于 0
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> queryBatchSize(int batchSize) {
            this.queryBatchSize = batchSize;
            return this;
        }

        /**
         * 设置处理分页大小（使用默认值 10000）
         * <p>
         * 当源数据量超过 pageSize 时，自动分页处理，降低峰值内存占用。
         * 配合 {@link #buildPage(java.util.function.Consumer)} 使用可实现流式处理。
         *
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> pageSize() {
            return pageSize(10000);
        }

        /**
         * 设置处理分页大小
         *
         * @param pageSize 每页处理的源数据条数，必须大于 0
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        /**
         * 添加共享数据
         * <p>
         * 用于在关联组装过程中传递预加载的数据，特别适合多层嵌套场景。
         * <p>
         * 使用示例：
         * <pre>{@code
         * Map<Long, Workshift> workshiftMap = workshiftMapper.selectBatchIds(ids);
         *
         * RelationAssembler.from(list, VO.class, converter::toVO)
         *     .withSharedData("workshiftMap", workshiftMap)
         *     .withRelation(..., (wg, context) -> {
         *         Map<Long, Workshift> map = context.getShared("workshiftMap");
         *         // 使用 map 组装对象
         *     }, ...)
         *     .build();
         * }</pre>
         *
         * @param key   数据键，用于在 converter 中通过 {@link AssemblyContext#getShared(String)} 获取
         * @param value 数据值，通常是预加载的 Map 或 List
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> withSharedData(String key, Object value) {
            this.sharedData.put(key, value);
            return this;
        }

        /**
         * 批量添加共享数据
         *
         * @param data 共享数据 Map，key 为数据键，value 为数据值
         * @return 当前构建器，用于链式调用
         */
        public Builder<S, T> withSharedData(Map<String, Object> data) {
            if (data != null) {
                this.sharedData.putAll(data);
            }
            return this;
        }

        /**
         * 添加关联对象配置（无类型转换）
         * <p>
         * 当查询的关联对象类型与 VO 字段类型一致时使用此方法。
         *
         * @param extractor        从源实体提取关联 ID 的函数，如 {@code Order::getUserId}
         * @param queryFunction    批量查询函数，接收 ID 集合返回关联对象列表，如 {@code ids -> userRepo.findAllById(ids)}
         * @param relationIdGetter 从关联对象获取 ID 的函数，如 {@code User::getId}
         * @param voSetter         将关联对象设置到 VO 的函数，如 {@code OrderVO::setUser}
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
         * 例如查询 User 实体但需要设置为 UserVO 类型。
         *
         * @param extractor        从源实体提取关联 ID 的函数，如 {@code Order::getUserId}
         * @param queryFunction    批量查询函数，接收 ID 集合返回关联对象列表，如 {@code ids -> userRepo.findAllById(ids)}
         * @param relationIdGetter 从关联对象获取 ID 的函数，如 {@code User::getId}
         * @param converter        转换函数，将关联对象转换为目标类型，如 {@code userMapper::toVO}
         * @param voSetter         将转换后的关联对象设置到 VO 的函数，如 {@code OrderVO::setUser}
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
         * 添加关联对象配置（带类型转换和上下文）
         * <p>
         * 当需要使用 {@link AssemblyContext} 中的共享数据进行转换时使用此方法。
         * 适用于多层嵌套场景，可以在 converter 中访问预加载的数据。
         * <p>
         * 使用示例：
         * <pre>{@code
         * RelationAssembler.from(list, VO.class, converter::toVO)
         *     .withSharedData("contactMap", contactMap)
         *     .withRelation(
         *         Order::getShippingInfoId,
         *         ids -> shippingInfoRepo.findAllById(ids),
         *         ShippingInfo::getId,
         *         (info, context) -> {
         *             Map<Long, Contact> map = context.getShared("contactMap");
         *             ShippingInfoVO vo = shippingInfoMapper.toVO(info);
         *             vo.setSender(map.get(info.getSenderId()));
         *             return vo;
         *         },
         *         OrderVO::setShippingInfo
         *     )
         *     .build();
         * }</pre>
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接收 ID 集合返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数，接收关联对象和 {@link AssemblyContext}，返回转换后的对象
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
                BiFunction<R, AssemblyContext, V> converter,
                BiConsumer<T, V> voSetter) {
            relations.add(new RelationConfigWithContext<>(extractor, queryFunction, relationIdGetter, converter, voSetter));
            return this;
        }

        /**
         * 添加关联对象配置（基于 List 的查询，无类型转换）
         * <p>
         * 当批量查询方法接受 {@link List} 而非 {@link Set} 时使用此方法。
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
         * 添加关联对象配置（基于 List 的查询，带类型转换和上下文）
         * <p>
         * 适用于多层嵌套场景，可以在 converter 中访问预加载的数据。
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接受 ID 列表返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数，接收关联对象和 {@link AssemblyContext}，返回转换后的对象
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
                BiFunction<R, AssemblyContext, V> converter,
                BiConsumer<T, V> voSetter) {
            relations.add(new RelationConfigWithListAndContext<>(extractor, queryFunction, relationIdGetter, converter, voSetter));
            return this;
        }

        /**
         * 添加嵌套关联对象配置（带类型转换）
         * <p>
         * 用于多层嵌套关联场景，库内部自动处理层级间的 ID 提取、批量查询和对象组装。
         * <p>
         * 使用示例：
         * <pre>{@code
         * RelationAssembler.from(orders, OrderVO.class, orderMapper::toVO)
         *     .parallel()
         *     .withNested(
         *         Order::getShippingInfoId,
         *         ids -> shippingInfoRepository.findAllById(ids),
         *         ShippingInfo::getId,
         *         shippingInfoMapper::toVO,
         *         OrderVO::setShippingInfo,
         *         nested -> nested
         *             .withRelation(ShippingInfo::getSenderId,
         *                 ids -> contactRepository.findAllById(ids),
         *                 Contact::getId, ShippingInfoVO::setSender)
         *             .withRelation(ShippingInfo::getReceiverId,
         *                 ids -> contactRepository.findAllById(ids),
         *                 Contact::getId, ShippingInfoVO::setReceiver)
         *     )
         *     .build();
         * }</pre>
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接收 ID 集合返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数，将关联对象转换为 VO 类型
         * @param voSetter         将转换后的对象设置到 VO 的函数
         * @param nested           嵌套关联配置回调，声明下一层的关联关系
         * @param <I>              ID 类型
         * @param <R>              关联对象类型
         * @param <V>              转换后的 VO 类型
         * @return 当前构建器
         */
        public <I, R, V> Builder<S, T> withNested(
                Function<S, I> extractor,
                Function<Set<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                Function<R, V> converter,
                BiConsumer<T, V> voSetter,
                Consumer<NestedBuilder<R, V>> nested) {
            NestedBuilder<R, V> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            relations.add(new NestedRelationConfig<>(
                    extractor, queryFunction, relationIdGetter, converter, voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 添加嵌套关联对象配置（无类型转换）
         * <p>
         * 当关联对象类型与 VO 字段类型一致时使用。
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param voSetter         将关联对象设置到 VO 的函数
         * @param nested           嵌套关联配置回调
         * @param <I>              ID 类型
         * @param <R>              关联对象类型
         * @return 当前构建器
         */
        public <I, R> Builder<S, T> withNested(
                Function<S, I> extractor,
                Function<Set<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                BiConsumer<T, R> voSetter,
                Consumer<NestedBuilder<R, R>> nested) {
            NestedBuilder<R, R> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            relations.add(new NestedRelationConfig<>(
                    extractor, queryFunction, relationIdGetter, Function.identity(), voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 添加嵌套关联对象配置（基于 List 查询，带类型转换）
         * <p>
         * 适用于 MyBatis Plus 等批量查询方法接受 List 参数的场景。
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数，接受 ID 列表返回关联对象列表
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数
         * @param voSetter         将转换后的对象设置到 VO 的函数
         * @param nested           嵌套关联配置回调
         * @param <I>              ID 类型
         * @param <R>              关联对象类型
         * @param <V>              转换后的 VO 类型
         * @return 当前构建器
         */
        public <I, R, V> Builder<S, T> withNestedList(
                Function<S, I> extractor,
                Function<List<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                Function<R, V> converter,
                BiConsumer<T, V> voSetter,
                Consumer<NestedBuilder<R, V>> nested) {
            NestedBuilder<R, V> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            relations.add(new NestedRelationConfigWithList<>(
                    extractor, queryFunction, relationIdGetter, converter, voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 添加嵌套关联对象配置（基于 List 查询，无类型转换）
         *
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param voSetter         将关联对象设置到 VO 的函数
         * @param nested           嵌套关联配置回调
         * @param <I>              ID 类型
         * @param <R>              关联对象类型
         * @return 当前构建器
         */
        public <I, R> Builder<S, T> withNestedList(
                Function<S, I> extractor,
                Function<List<I>, List<R>> queryFunction,
                Function<R, I> relationIdGetter,
                BiConsumer<T, R> voSetter,
                Consumer<NestedBuilder<R, R>> nested) {
            NestedBuilder<R, R> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            relations.add(new NestedRelationConfigWithList<>(
                    extractor, queryFunction, relationIdGetter, Function.identity(), voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 构建 VO 列表
         * <p>
         * 执行流程：
         * <ol>
         *   <li>执行所有配置的关联对象批量查询（并行或串行，支持分批）</li>
         *   <li>解析嵌套关联的子级查询</li>
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

            // 分页处理
            if (pageSize > 0 && sourceList.size() > pageSize) {
                return buildWithPaging();
            }

            return doBuild(sourceList);
        }

        /**
         * 流式分页构建，每处理完一页数据就回调，峰值内存只占一页
         * <p>
         * 如果未设置 {@link #pageSize()}，默认每页 10000 条。
         *
         * @param consumer 每页结果的消费者
         */
        public void buildPage(Consumer<List<T>> consumer) {
            if (CollectionUtils.isEmpty(sourceList)) {
                return;
            }

            int effectivePageSize = pageSize > 0 ? pageSize : 10000;
            int totalSize = sourceList.size();
            for (int start = 0; start < totalSize; start += effectivePageSize) {
                int end = Math.min(start + effectivePageSize, totalSize);
                List<S> page = new ArrayList<>(sourceList.subList(start, end));
                consumer.accept(doBuild(page));
            }
        }

        /**
         * 解析嵌套关联的子级查询
         * <p>
         * 遍历所有关联配置，对嵌套关联（NestedRelationConfig）执行子级查询。
         * 支持并行执行多个嵌套配置的子级查询。
         */
        @SuppressWarnings("rawtypes")
        private void resolveNestedChildren(List<Map<?, ?>> maps) {
            List<NestedRelationConfig> nestedConfigs = new ArrayList<>();
            List<Integer> nestedIndices = new ArrayList<>();
            for (int i = 0; i < relations.size(); i++) {
                if (relations.get(i) instanceof NestedRelationConfig) {
                    nestedConfigs.add((NestedRelationConfig) relations.get(i));
                    nestedIndices.add(i);
                }
            }

            if (nestedConfigs.isEmpty()) return;

            if (parallel && nestedConfigs.size() > 1) {
                List<CompletableFuture<Void>> futures = new ArrayList<>(nestedConfigs.size());
                for (int i = 0; i < nestedConfigs.size(); i++) {
                    final int idx = nestedIndices.get(i);
                    final NestedRelationConfig nc = nestedConfigs.get(i);
                    futures.add(CompletableFuture.runAsync(
                            () -> nc.resolveChildren(new ArrayList<>(maps.get(idx).values()), queryBatchSize),
                            executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } else {
                for (int i = 0; i < nestedConfigs.size(); i++) {
                    nestedConfigs.get(i).resolveChildren(new ArrayList<>(maps.get(nestedIndices.get(i)).values()), queryBatchSize);
                }
            }
        }

        /**
         * 核心构建逻辑（不分页）
         */
        private List<T> doBuild(List<S> sources) {
            long buildStart = System.nanoTime();
            boolean isDebugEnabled = log.isDebugEnabled();

            if (isDebugEnabled) {
                log.debug("[RelationAssembler] 开始组装: source={}, relations={}, parallel={}, fast={}, batchSize={}, pageSize={}, voType={}",
                        sources.size(), relations.size(), parallel, fast, queryBatchSize, pageSize,
                        voClass.getSimpleName());
            }

            if (log.isTraceEnabled()) {
                String sourceType = sources.isEmpty() ? "empty" : sources.get(0).getClass().getSimpleName();
                log.trace("[RelationAssembler] === 开始组装: {}条 {} -> {} ===", sources.size(), sourceType, voClass.getSimpleName());
            }

            AssemblyContext context = new AssemblyContext(sharedData, executor, sources);

            long queryStart = System.nanoTime();
            List<Map<?, ?>> maps;
            if (parallel && relations.size() > 1) {
                maps = queryRelationsInParallel(sources, context);
            } else {
                maps = queryRelationsSequentially(sources, context);
            }
            long queryTime = (System.nanoTime() - queryStart) / 1_000_000;

            if (isDebugEnabled) {
                log.debug("[RelationAssembler] 查询阶段完成: 耗时={}ms, 关联查询数={}", queryTime, maps.size());
            }

            if (log.isTraceEnabled()) {
                for (int i = 0; i < maps.size(); i++) {
                    log.trace("[RelationAssembler] [关系 {}/{}] 映射条目={}, 源数据条目={}", i + 1, maps.size(), maps.get(i).size(), sources.size());
                }
            }

            // 解析嵌套关联的子级查询
            resolveNestedChildren(maps);

            long convertStart = System.nanoTime();
            List<T> result;
            if (fast) {
                result = buildInParallel(sources, maps, context);
            } else {
                result = buildSequentially(sources, maps, context);
            }
            long convertTime = (System.nanoTime() - convertStart) / 1_000_000;
            long totalTime = (System.nanoTime() - buildStart) / 1_000_000;

            if (isDebugEnabled) {
                log.debug("[RelationAssembler] VO转换完成: 数量={}, 耗时={}ms", result.size(), convertTime);
            }
            log.info("[RelationAssembler] 组装完成: 数量={}, 查询耗时={}ms, 转换耗时={}ms, 总耗时={}ms",
                    result.size(), queryTime, convertTime, totalTime);

            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] === 组装完成: {}条 {} ===", result.size(), voClass.getSimpleName());
            }

            return result;
        }

        /**
         * 分页构建，内部分页处理后合并结果
         */
        private List<T> buildWithPaging() {
            int totalSize = sourceList.size();
            int totalPages = (totalSize + pageSize - 1) / pageSize;
            log.debug("[RelationAssembler] 分页构建: 总数据={}, 每页={}, 总页数={}", totalSize, pageSize, totalPages);

            List<T> allResults = new ArrayList<>(totalSize);
            for (int start = 0; start < totalSize; start += pageSize) {
                int end = Math.min(start + pageSize, totalSize);
                List<S> page = new ArrayList<>(sourceList.subList(start, end));
                allResults.addAll(doBuild(page));
            }
            return allResults;
        }

        /**
         * 串行组装 VO
         */
        @SuppressWarnings("unchecked")
        private List<T> buildSequentially(List<S> sources, List<Map<?, ?>> maps, AssemblyContext context) {
            List<T> voList = new ArrayList<>(sources.size());
            int relSize = relations.size();
            BaseRelationConfig<?, ?>[] relArray = relations.toArray(new BaseRelationConfig[0]);
            Map<?, ?>[] mapArray = maps.toArray(new Map[0]);
            for (S source : sources) {
                try {
                    T vo = copier.copy(source, voSupplier);

                    if (vo != null) {
                        for (int i = 0; i < relSize; i++) {
                            relArray[i].setRelation(vo, mapArray[i], source, context);
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
         * 并行组装 VO，按 CPU 核心数分片并行处理
         */
        @SuppressWarnings("unchecked")
        private List<T> buildInParallel(List<S> sources, List<Map<?, ?>> maps, AssemblyContext context) {
            final int size = sources.size();
            final int cpuCores = Runtime.getRuntime().availableProcessors();
            final int batch = Math.max(1, size / (cpuCores * 4));
            final int relSize = relations.size();
            final BaseRelationConfig<?, ?>[] relArray = relations.toArray(new BaseRelationConfig[0]);
            final Map<?, ?>[] mapArray = maps.toArray(new Map[0]);

            List<CompletableFuture<List<T>>> futures = new ArrayList<>();

            for (int start = 0; start < size; start += batch) {
                int end = Math.min(start + batch, size);
                List<S> subBatch = new ArrayList<>(sources.subList(start, end));

                CompletableFuture<List<T>> future = CompletableFuture.supplyAsync(() -> {
                    List<T> batchResult = new ArrayList<>(subBatch.size());
                    for (S source : subBatch) {
                        try {
                            T vo = copier.copy(source, voSupplier);

                            if (vo != null) {
                                for (int i = 0; i < relSize; i++) {
                                    relArray[i].setRelation(vo, mapArray[i], source, context);
                                }
                                batchResult.add(vo);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("组装 VO 失败: " + e.getMessage(), e);
                        }
                    }
                    return batchResult;
                }, executor);

                futures.add(future);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                List<T> voList = new ArrayList<>(size);
                for (int i = 0, len = futures.size(); i < len; i++) {
                    voList.addAll(futures.get(i).join());
                }
                return voList;
            } catch (Exception e) {
                throw new RuntimeException("并行组装 VO 失败: " + e.getMessage(), e);
            }
        }

        /**
         * 串行执行所有关联对象查询
         */
        private List<Map<?, ?>> queryRelationsSequentially(List<S> sources, AssemblyContext context) {
            int size = relations.size();
            List<Map<?, ?>> maps = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                maps.add(relations.get(i).queryAndBuildMap(sources, context, queryBatchSize));
            }
            return maps;
        }

        /**
         * 使用 CompletableFuture 并行执行所有关联对象查询
         */
        @SuppressWarnings("unchecked")
        private List<Map<?, ?>> queryRelationsInParallel(List<S> sources, AssemblyContext context) {
            int size = relations.size();
            final int batchSize = queryBatchSize;
            List<CompletableFuture<Map<?, ?>>> futures = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                BaseRelationConfig<?, ?> relation = relations.get(i);
                CompletableFuture<Map<?, ?>> future = CompletableFuture.supplyAsync(
                        () -> relation.queryAndBuildMap(sources, context, batchSize),
                        executor
                );
                futures.add(future);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                List<Map<?, ?>> maps = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    maps.add(futures.get(i).join());
                }
                return maps;
            } catch (Exception e) {
                throw new RuntimeException("并行查询执行失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 嵌套关联构建器
     * <p>
     * 用于配置嵌套关联对象的子级关联关系。通过 {@link Builder#withNested} 的回调参数获取实例。
     * <p>
     * 支持的配置项与 {@link Builder} 一致，包括：
     * <ul>
     *   <li>{@link #withRelation} - 添加子级关联（Set 查询）</li>
     *   <li>{@link #withRelationList} - 添加子级关联（List 查询，MyBatis Plus）</li>
     *   <li>{@link #withNested} - 添加更深层的嵌套关联</li>
     *   <li>{@link #withNestedList} - 添加更深层的嵌套关联（List 查询）</li>
     * </ul>
     *
     * @param <R> 父级关联对象类型（如 ShippingInfo）
     * @param <V> 父级关联 VO 类型（如 ShippingInfoVO）
     */
    public static class NestedBuilder<R, V> {
        private final List<BaseRelationConfig<?, ?>> childRelations = new ArrayList<>();
        private final List<NestedRelationConfig<?, ?, ?>> childNestedRelations = new ArrayList<>();
        private final boolean parallel;
        private final Executor executor;

        NestedBuilder(boolean parallel, Executor executor) {
            this.parallel = parallel;
            this.executor = executor;
        }

        /**
         * 添加子级关联对象配置（无类型转换）
         *
         * @param extractor        从父级关联对象提取关联 ID 的函数
         * @param queryFunction    批量查询函数
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param voSetter         将关联对象设置到父级 VO 的函数
         * @param <I2>             ID 类型
         * @param <R2>             关联对象类型
         * @return 当前构建器
         */
        public <I2, R2> NestedBuilder<R, V> withRelation(
                Function<R, I2> extractor,
                Function<Set<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                BiConsumer<V, R2> voSetter) {
            childRelations.add(new RelationConfig<>(extractor, queryFunction, relationIdGetter, voSetter));
            return this;
        }

        /**
         * 添加子级关联对象配置（带类型转换）
         *
         * @param extractor        从父级关联对象提取关联 ID 的函数
         * @param queryFunction    批量查询函数
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数
         * @param voSetter         将转换后的对象设置到父级 VO 的函数
         * @param <I2>             ID 类型
         * @param <R2>             查询返回的关联对象类型
         * @param <V2>             转换后的类型
         * @return 当前构建器
         */
        public <I2, R2, V2> NestedBuilder<R, V> withRelation(
                Function<R, I2> extractor,
                Function<Set<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                Function<R2, V2> converter,
                BiConsumer<V, V2> voSetter) {
            childRelations.add(new RelationConfigWithConverter<>(extractor, queryFunction, relationIdGetter, converter, voSetter));
            return this;
        }

        /**
         * 添加子级关联对象配置（基于 List 查询，无类型转换）
         */
        public <I2, R2> NestedBuilder<R, V> withRelationList(
                Function<R, I2> extractor,
                Function<List<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                BiConsumer<V, R2> voSetter) {
            childRelations.add(new RelationConfigWithList<>(extractor, queryFunction, relationIdGetter, voSetter));
            return this;
        }

        /**
         * 添加子级关联对象配置（基于 List 查询，带类型转换）
         */
        public <I2, R2, V2> NestedBuilder<R, V> withRelationList(
                Function<R, I2> extractor,
                Function<List<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                Function<R2, V2> converter,
                BiConsumer<V, V2> voSetter) {
            childRelations.add(new RelationConfigWithListAndConverter<>(extractor, queryFunction, relationIdGetter, converter, voSetter));
            return this;
        }

        /**
         * 添加更深层的嵌套关联（带类型转换）
         *
         * @param extractor        从父级关联对象提取关联 ID 的函数
         * @param queryFunction    批量查询函数
         * @param relationIdGetter 从关联对象获取 ID 的函数
         * @param converter        转换函数
         * @param voSetter         将转换后的对象设置到父级 VO 的函数
         * @param nested           更深层的嵌套关联配置回调
         * @param <I2>             ID 类型
         * @param <R2>             关联对象类型
         * @param <V2>             转换后的类型
         * @return 当前构建器
         */
        public <I2, R2, V2> NestedBuilder<R, V> withNested(
                Function<R, I2> extractor,
                Function<Set<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                Function<R2, V2> converter,
                BiConsumer<V, V2> voSetter,
                Consumer<NestedBuilder<R2, V2>> nested) {
            NestedBuilder<R2, V2> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            childNestedRelations.add(new NestedRelationConfig<>(
                    extractor, queryFunction, relationIdGetter, converter, voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 添加更深层的嵌套关联（无类型转换）
         */
        public <I2, R2> NestedBuilder<R, V> withNested(
                Function<R, I2> extractor,
                Function<Set<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                BiConsumer<V, R2> voSetter,
                Consumer<NestedBuilder<R2, R2>> nested) {
            NestedBuilder<R2, R2> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            childNestedRelations.add(new NestedRelationConfig<>(
                    extractor, queryFunction, relationIdGetter, Function.identity(), voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 添加更深层的嵌套关联（基于 List 查询，带类型转换）
         */
        public <I2, R2, V2> NestedBuilder<R, V> withNestedList(
                Function<R, I2> extractor,
                Function<List<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                Function<R2, V2> converter,
                BiConsumer<V, V2> voSetter,
                Consumer<NestedBuilder<R2, V2>> nested) {
            NestedBuilder<R2, V2> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            childNestedRelations.add(new NestedRelationConfigWithList<>(
                    extractor, queryFunction, relationIdGetter, converter, voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }

        /**
         * 添加更深层的嵌套关联（基于 List 查询，无类型转换）
         */
        public <I2, R2> NestedBuilder<R, V> withNestedList(
                Function<R, I2> extractor,
                Function<List<I2>, List<R2>> queryFunction,
                Function<R2, I2> relationIdGetter,
                BiConsumer<V, R2> voSetter,
                Consumer<NestedBuilder<R2, R2>> nested) {
            NestedBuilder<R2, R2> childBuilder = new NestedBuilder<>(parallel, executor);
            nested.accept(childBuilder);
            childNestedRelations.add(new NestedRelationConfigWithList<>(
                    extractor, queryFunction, relationIdGetter, Function.identity(), voSetter,
                    childBuilder.childRelations, childBuilder.childNestedRelations, parallel, executor));
            return this;
        }
    }

    /**
     * 关联对象配置基类
     * <p>
     * 封装了从源列表提取 ID、执行批量查询、构建 ID 映射的通用逻辑。
     *
     * @param <I> ID 类型
     * @param <R> 关联对象类型
     */
    @SuppressWarnings("rawtypes")
    private abstract static class BaseRelationConfig<I, R> {
        static final Logger log = LoggerFactory.getLogger(BaseRelationConfig.class);

        /** 从源实体提取关联 ID 的函数 */
        protected final Function extractor;
        /** 批量查询函数 */
        protected final Function queryFunction;
        /** 从关联对象获取 ID 的函数 */
        protected final Function relationIdGetter;

        /**
         * @param extractor        从源实体提取关联 ID 的函数
         * @param queryFunction    批量查询函数
         * @param relationIdGetter 从关联对象获取 ID 的函数
         */
        protected BaseRelationConfig(
                Function extractor,
                Function queryFunction,
                Function relationIdGetter) {
            this.extractor = extractor;
            this.queryFunction = queryFunction;
            this.relationIdGetter = relationIdGetter;
        }

        /**
         * 格式化 ID 集合用于日志输出，超过 10 个时截断显示
         */
        protected static String formatIds(Collection<?> ids) {
            if (ids.size() <= 10) {
                return ids.toString();
            }
            StringBuilder sb = new StringBuilder("[");
            int count = 0;
            for (Object id : ids) {
                if (count > 0) sb.append(", ");
                sb.append(id);
                if (++count >= 10) break;
            }
            sb.append(", ...共").append(ids.size()).append("个]");
            return sb.toString();
        }

        /**
         * 从源列表提取 ID，执行批量查询，构建 ID 到关联对象的映射
         */
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList) {
            return queryAndBuildMap(sourceList, null, 0);
        }

        /**
         * 从源列表提取 ID，执行批量查询，构建 ID 到关联对象的映射（带上下文）
         */
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context) {
            return queryAndBuildMap(sourceList, context, 0);
        }

        /**
         * 从源列表提取 ID，执行批量查询，构建 ID 到关联对象的映射（带上下文和分批）
         *
         * @param sourceList 源实体列表
         * @param context    组装上下文，可为 null
         * @param batchSize  查询分批大小，0 表示不分批
         * @return ID 到关联对象的映射
         */
        @SuppressWarnings("unchecked")
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context, int batchSize) {
            int size = sourceList.size();
            Set<I> ids = new HashSet<>(size * 4 / 3 + 1);
            for (int i = 0; i < size; i++) {
                I id = (I) extractor.apply(sourceList.get(i));
                if (id != null) {
                    ids.add(id);
                }
            }

            if (ids.isEmpty()) {
                log.trace("[RelationAssembler] [query] 未提取到任何ID, 跳过查询");
                return Collections.emptyMap();
            }

            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] [query] 提取ID详情: 唯一ID数={}, IDs={}", ids.size(), formatIds(ids));
            }

            // 分批查询或一次性查询
            List<R> relations;
            if (batchSize > 0 && ids.size() > batchSize) {
                log.debug("[RelationAssembler] [query] 提取到{}个唯一ID, 启用分批查询, batchSize={}", ids.size(), batchSize);
                relations = batchQuery(ids, batchSize);
            } else {
                log.debug("[RelationAssembler] [query] 提取到{}个唯一ID, 一次查询", ids.size());
                long start = System.nanoTime();
                relations = (List<R>) queryFunction.apply(ids);
                long time = (System.nanoTime() - start) / 1_000_000;
                log.debug("[RelationAssembler] [query] 查询完成: 耗时={}ms, 返回{}条结果", time, relations.size());
            }

            return buildResultMap(relations);
        }

        /**
         * 分批查询关联对象
         */
        @SuppressWarnings("unchecked")
        private List<R> batchQuery(Set<I> ids, int batchSize) {
            List<I> idList = new ArrayList<>(ids);
            int totalBatches = (idList.size() + batchSize - 1) / batchSize;
            List<R> allRelations = new ArrayList<>(ids.size());
            long totalQueryTime = 0;

            for (int i = 0; i < idList.size(); i += batchSize) {
                int batchNum = i / batchSize + 1;
                int end = Math.min(i + batchSize, idList.size());
                Set<I> batchIds = new HashSet<>(idList.subList(i, end));
                long start = System.nanoTime();
                List<R> batchResult = (List<R>) queryFunction.apply(batchIds);
                long batchTime = (System.nanoTime() - start) / 1_000_000;
                totalQueryTime += batchTime;
                allRelations.addAll(batchResult);
                log.trace("[RelationAssembler] [query] 批次{}/{}: {}个ID, 耗时={}ms, 返回{}条",
                        batchNum, totalBatches, batchIds.size(), batchTime, batchResult.size());
            }

            log.debug("[RelationAssembler] [query] 分批查询完成: {}批, 总耗时={}ms, 总结果={}条", totalBatches, totalQueryTime, allRelations.size());
            return allRelations;
        }

        /**
         * 将查询结果构建为 ID → 对象映射
         */
        @SuppressWarnings("unchecked")
        protected Map<I, ?> buildResultMap(List<R> relations) {
            int relSize = relations.size();
            Map<I, Object> map = new HashMap<>(relSize * 4 / 3 + 1);
            for (int i = 0; i < relSize; i++) {
                R r = relations.get(i);
                I id = (I) relationIdGetter.apply(r);
                if (id != null) {
                    map.put(id, r);
                }
            }
            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] [query] 映射构建完成: 映射条目={}, 查询结果={}, 重复ID丢弃={}", map.size(), relSize, relSize - map.size());
            }
            return map;
        }

        /**
         * 设置关联对象到 VO
         *
         * @param vo          目标 VO
         * @param relationMap 关联对象的 ID 映射
         * @param source      源实体
         */
        protected abstract void setRelation(Object vo, Map<?, ?> relationMap, Object source);

        /**
         * 设置关联对象到 VO（带上下文）
         *
         * @param vo          目标 VO
         * @param relationMap 关联对象的 ID 映射
         * @param source      源实体
         * @param context     组装上下文
         */
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source, AssemblyContext context) {
            setRelation(vo, relationMap, source);
        }
    }

    /**
     * 无类型转换的关联对象配置
     * <p>
     * 直接将查询到的关联对象设置到 VO，不做类型转换。
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfig<I, R> extends BaseRelationConfig<I, R> {
        /** 将关联对象设置到 VO 的函数 */
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
     * <p>
     * 查询到的关联对象经过 converter 转换后再设置到 VO。
     * 例如 User → UserVO 的转换。
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithConverter<I, R, V> extends BaseRelationConfig<I, R> {
        /** 转换函数，将关联对象转换为目标类型 */
        private final Function<R, V> converter;
        /** 将转换后的对象设置到 VO 的函数 */
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
     * 带类型转换和上下文的关联对象配置
     * <p>
     * 查询到的关联对象经过带 {@link AssemblyContext} 的 converter 转换后再设置到 VO。
     * 适用于多层嵌套场景，可在 converter 中访问共享数据。
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithContext<I, R, V> extends BaseRelationConfig<I, R> {
        /** 带上下文的转换函数 */
        private final BiFunction<R, AssemblyContext, V> converter;
        /** 将转换后的对象设置到 VO 的函数 */
        private final BiConsumer voSetter;

        private RelationConfigWithContext(
                Function extractor,
                Function queryFunction,
                Function relationIdGetter,
                BiFunction<R, AssemblyContext, V> converter,
                BiConsumer voSetter) {
            super(extractor, queryFunction, relationIdGetter);
            this.converter = converter;
            this.voSetter = voSetter;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            setRelation(vo, relationMap, source, new AssemblyContext(new HashMap<>(), ForkJoinPool.commonPool(), null));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source, AssemblyContext context) {
            I id = (I) extractor.apply(source);
            R relation = (R) relationMap.get(id);
            if (relation != null) {
                V converted = converter.apply(relation, context);
                ((BiConsumer) voSetter).accept(vo, converted);
            }
        }
    }

    /**
     * 基于 List 查询的关联对象配置（无类型转换）
     * <p>
     * 与 {@link RelationConfig} 类似，但批量查询函数接受 {@link List} 而非 {@link Set}。
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithList<I, R> extends BaseRelationConfig<I, R> {
        /** 基于 List 的批量查询函数 */
        private final Function<List<I>, List<R>> queryFunctionWithList;
        /** 将关联对象设置到 VO 的函数 */
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
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context) {
            return queryAndBuildMap(sourceList, context, 0);
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context, int batchSize) {
            int size = sourceList.size();
            Set<I> seen = new HashSet<>(size * 4 / 3 + 1);
            List<I> idList = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                I id = (I) extractor.apply(sourceList.get(i));
                if (id != null && seen.add(id)) {
                    idList.add(id);
                }
            }

            if (idList.isEmpty()) {
                return Collections.emptyMap();
            }

            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] [query] 提取ID详情(List): 唯一ID数={}, IDs={}", idList.size(), formatIds(idList));
            }

            // 分批查询或一次性查询
            List<R> relations;
            if (batchSize > 0 && idList.size() > batchSize) {
                log.debug("[RelationAssembler] [query] 提取到{}个唯一ID(List), 启用分批查询, batchSize={}", idList.size(), batchSize);
                relations = batchQueryList(idList, batchSize);
            } else {
                log.debug("[RelationAssembler] [query] 提取到{}个唯一ID(List), 一次查询", idList.size());
                long start = System.nanoTime();
                relations = queryFunctionWithList.apply(idList);
                long time = (System.nanoTime() - start) / 1_000_000;
                log.debug("[RelationAssembler] [query] 查询完成: 耗时={}ms, 返回{}条结果", time, relations.size());
            }

            return buildResultMap(relations);
        }

        /**
         * 分批查询（List 版本）
         */
        private List<R> batchQueryList(List<I> idList, int batchSize) {
            int totalBatches = (idList.size() + batchSize - 1) / batchSize;
            List<R> allRelations = new ArrayList<>(idList.size());
            long totalQueryTime = 0;
            for (int i = 0; i < idList.size(); i += batchSize) {
                int batchNum = i / batchSize + 1;
                int end = Math.min(i + batchSize, idList.size());
                List<I> batchIds = idList.subList(i, end);
                long start = System.nanoTime();
                List<R> batchResult = queryFunctionWithList.apply(batchIds);
                long batchTime = (System.nanoTime() - start) / 1_000_000;
                totalQueryTime += batchTime;
                allRelations.addAll(batchResult);
                log.trace("[RelationAssembler] [query] 批次{}/{}: {}个ID, 耗时={}ms, 返回{}条",
                        batchNum, totalBatches, batchIds.size(), batchTime, batchResult.size());
            }
            log.debug("[RelationAssembler] [query] 分批查询完成: {}批, 总耗时={}ms, 总结果={}条", totalBatches, totalQueryTime, allRelations.size());
            return allRelations;
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
     * <p>
     * 查询到的关联对象经过 converter 转换后再设置到 VO。
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithListAndConverter<I, R, V> extends BaseRelationConfig<I, R> {
        /** 基于 List 的批量查询函数 */
        private final Function<List<I>, List<R>> queryFunctionWithList;
        /** 转换函数 */
        private final Function<R, V> converter;
        /** 将转换后的对象设置到 VO 的函数 */
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
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context) {
            return queryAndBuildMap(sourceList, context, 0);
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context, int batchSize) {
            int size = sourceList.size();
            Set<I> seen = new HashSet<>(size * 4 / 3 + 1);
            List<I> idList = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                I id = (I) extractor.apply(sourceList.get(i));
                if (id != null && seen.add(id)) {
                    idList.add(id);
                }
            }

            if (idList.isEmpty()) {
                return Collections.emptyMap();
            }

            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] [query] 提取ID详情(List): 唯一ID数={}, IDs={}", idList.size(), formatIds(idList));
            }

            List<R> relations;
            if (batchSize > 0 && idList.size() > batchSize) {
                relations = batchQueryList(idList, batchSize);
            } else {
                long start = System.nanoTime();
                relations = queryFunctionWithList.apply(idList);
                long time = (System.nanoTime() - start) / 1_000_000;
                if (log.isTraceEnabled()) {
                    log.trace("[RelationAssembler] [query] 查询完成(List): 耗时={}ms, 返回{}条结果", time, relations.size());
                }
            }

            return buildResultMap(relations);
        }

        private List<R> batchQueryList(List<I> idList, int batchSize) {
            int totalBatches = (idList.size() + batchSize - 1) / batchSize;
            List<R> allRelations = new ArrayList<>(idList.size());
            long totalQueryTime = 0;
            for (int i = 0; i < idList.size(); i += batchSize) {
                int batchNum = i / batchSize + 1;
                int end = Math.min(i + batchSize, idList.size());
                List<I> batchIds = idList.subList(i, end);
                long start = System.nanoTime();
                List<R> batchResult = queryFunctionWithList.apply(batchIds);
                long batchTime = (System.nanoTime() - start) / 1_000_000;
                totalQueryTime += batchTime;
                allRelations.addAll(batchResult);
                log.trace("[RelationAssembler] [query] 批次{}/{}: {}个ID, 耗时={}ms, 返回{}条",
                        batchNum, totalBatches, batchIds.size(), batchTime, batchResult.size());
            }
            log.debug("[RelationAssembler] [query] 分批查询完成: {}批, 总耗时={}ms, 总结果={}条", totalBatches, totalQueryTime, allRelations.size());
            return allRelations;
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
     * 基于 List 查询的关联对象配置（带类型转换和上下文）
     * <p>
     * 查询到的关联对象经过带 {@link AssemblyContext} 的 converter 转换后再设置到 VO。
     * 适用于多层嵌套场景，可在 converter 中访问共享数据。
     */
    @SuppressWarnings("rawtypes")
    private static class RelationConfigWithListAndContext<I, R, V> extends BaseRelationConfig<I, R> {
        /** 基于 List 的批量查询函数 */
        private final Function<List<I>, List<R>> queryFunctionWithList;
        /** 带上下文的转换函数 */
        private final BiFunction<R, AssemblyContext, V> converter;
        /** 将转换后的对象设置到 VO 的函数 */
        private final BiConsumer voSetter;

        private RelationConfigWithListAndContext(
                Function extractor,
                Function<List<I>, List<R>> queryFunctionWithList,
                Function relationIdGetter,
                BiFunction<R, AssemblyContext, V> converter,
                BiConsumer voSetter) {
            super(extractor, null, relationIdGetter);
            this.queryFunctionWithList = queryFunctionWithList;
            this.converter = converter;
            this.voSetter = voSetter;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context) {
            return queryAndBuildMap(sourceList, context, 0);
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context, int batchSize) {
            int size = sourceList.size();
            Set<I> seen = new HashSet<>(size * 4 / 3 + 1);
            List<I> idList = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                I id = (I) extractor.apply(sourceList.get(i));
                if (id != null && seen.add(id)) {
                    idList.add(id);
                }
            }

            if (idList.isEmpty()) {
                return Collections.emptyMap();
            }

            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] [query] 提取ID详情(List): 唯一ID数={}, IDs={}", idList.size(), formatIds(idList));
            }

            List<R> relations;
            if (batchSize > 0 && idList.size() > batchSize) {
                relations = batchQueryList(idList, batchSize);
            } else {
                long start = System.nanoTime();
                relations = queryFunctionWithList.apply(idList);
                long time = (System.nanoTime() - start) / 1_000_000;
                if (log.isTraceEnabled()) {
                    log.trace("[RelationAssembler] [query] 查询完成(List): 耗时={}ms, 返回{}条结果", time, relations.size());
                }
            }

            return buildResultMap(relations);
        }

        private List<R> batchQueryList(List<I> idList, int batchSize) {
            int totalBatches = (idList.size() + batchSize - 1) / batchSize;
            List<R> allRelations = new ArrayList<>(idList.size());
            long totalQueryTime = 0;
            for (int i = 0; i < idList.size(); i += batchSize) {
                int batchNum = i / batchSize + 1;
                int end = Math.min(i + batchSize, idList.size());
                List<I> batchIds = idList.subList(i, end);
                long start = System.nanoTime();
                List<R> batchResult = queryFunctionWithList.apply(batchIds);
                long batchTime = (System.nanoTime() - start) / 1_000_000;
                totalQueryTime += batchTime;
                allRelations.addAll(batchResult);
                log.trace("[RelationAssembler] [query] 批次{}/{}: {}个ID, 耗时={}ms, 返回{}条",
                        batchNum, totalBatches, batchIds.size(), batchTime, batchResult.size());
            }
            log.debug("[RelationAssembler] [query] 分批查询完成: {}批, 总耗时={}ms, 总结果={}条", totalBatches, totalQueryTime, allRelations.size());
            return allRelations;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            setRelation(vo, relationMap, source, new AssemblyContext(new HashMap<>(), ForkJoinPool.commonPool(), null));
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source, AssemblyContext context) {
            I id = (I) extractor.apply(source);
            R relation = (R) relationMap.get(id);
            if (relation != null) {
                V converted = converter.apply(relation, context);
                ((BiConsumer) voSetter).accept(vo, converted);
            }
        }
    }

    /**
     * 嵌套关联对象配置（基于 Set 查询）
     * <p>
     * 支持在关联对象上继续声明子级关联关系，库内部自动处理
     * 层级间的 ID 提取、批量查询和对象组装。
     *
     * @param <I> ID 类型
     * @param <R> 关联对象类型
     * @param <V> 转换后的 VO 类型
     */
    @SuppressWarnings("rawtypes")
    private static class NestedRelationConfig<I, R, V> extends BaseRelationConfig<I, R> {
        private final Function<R, V> converter;
        private final BiConsumer voSetter;
        private final List<BaseRelationConfig<?, ?>> childRelations;
        private final List<NestedRelationConfig<?, ?, ?>> childNestedRelations;
        private final boolean parallel;
        private final Executor executor;

        /** 子级关联查询结果（查询阶段填充） */
        private List<Map<?, ?>> childMapList;
        /** 子级嵌套关联查询结果（查询阶段填充） */
        private List<Map<?, ?>> childNestedMapList;

        NestedRelationConfig(
                Function extractor,
                Function queryFunction,
                Function relationIdGetter,
                Function<R, V> converter,
                BiConsumer voSetter,
                List<BaseRelationConfig<?, ?>> childRelations,
                List<NestedRelationConfig<?, ?, ?>> childNestedRelations,
                boolean parallel,
                Executor executor) {
            super(extractor, queryFunction, relationIdGetter);
            this.converter = converter;
            this.voSetter = voSetter;
            this.childRelations = childRelations;
            this.childNestedRelations = childNestedRelations;
            this.parallel = parallel;
            this.executor = executor;
        }

        /**
         * 执行子级关联查询
         *
         * @param parentResults 父级查询结果列表
         * @param batchSize     查询分批大小
         */
        void resolveChildren(List<?> parentResults, int batchSize) {
            if (parentResults.isEmpty()) return;

            // 查询子级关联
            if (!childRelations.isEmpty()) {
                if (parallel && childRelations.size() > 1) {
                    childMapList = new ArrayList<>(childRelations.size());
                    List<CompletableFuture<Map<?, ?>>> futures = new ArrayList<>(childRelations.size());
                    for (BaseRelationConfig<?, ?> child : childRelations) {
                        futures.add(CompletableFuture.supplyAsync(
                                () -> child.queryAndBuildMap(parentResults, null, batchSize), executor));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    for (CompletableFuture<Map<?, ?>> f : futures) {
                        childMapList.add(f.join());
                    }
                } else {
                    childMapList = new ArrayList<>(childRelations.size());
                    for (BaseRelationConfig<?, ?> child : childRelations) {
                        childMapList.add(child.queryAndBuildMap(parentResults, null, batchSize));
                    }
                }
            }

            // 查询子级嵌套关联
            if (!childNestedRelations.isEmpty()) {
                childNestedMapList = new ArrayList<>(childNestedRelations.size());
                if (parallel && childNestedRelations.size() > 1) {
                    @SuppressWarnings("unchecked")
                    Map<?, ?>[] cnMaps = new Map[childNestedRelations.size()];
                    List<CompletableFuture<Void>> futures = new ArrayList<>(childNestedRelations.size());
                    for (int i = 0; i < childNestedRelations.size(); i++) {
                        final int idx = i;
                        final NestedRelationConfig<?, ?, ?> cn = childNestedRelations.get(i);
                        futures.add(CompletableFuture.runAsync(() -> {
                            Map<?, ?> cnMap = cn.queryAndBuildMap(parentResults, null, batchSize);
                            cnMaps[idx] = cnMap;
                            cn.resolveChildren(new ArrayList<>(cnMap.values()), batchSize);
                        }, executor));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    childNestedMapList = new ArrayList<>(cnMaps.length);
                    for (Map<?, ?> m : cnMaps) {
                        childNestedMapList.add(m);
                    }
                } else {
                    for (NestedRelationConfig<?, ?, ?> cn : childNestedRelations) {
                        Map<?, ?> cnMap = cn.queryAndBuildMap(parentResults, null, batchSize);
                        childNestedMapList.add(cnMap);
                        cn.resolveChildren(new ArrayList<>(cnMap.values()), batchSize);
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source) {
            I id = (I) extractor.apply(source);
            R relation = (R) relationMap.get(id);
            if (relation != null) {
                V converted = converter.apply(relation);

                // 设置子级关联
                if (childMapList != null) {
                    for (int i = 0; i < childRelations.size(); i++) {
                        childRelations.get(i).setRelation(converted, childMapList.get(i), relation);
                    }
                }

                // 设置子级嵌套关联
                if (childNestedMapList != null) {
                    for (int i = 0; i < childNestedRelations.size(); i++) {
                        childNestedRelations.get(i).setRelation(converted, childNestedMapList.get(i), relation);
                    }
                }

                ((BiConsumer) voSetter).accept(vo, converted);
            }
        }

        @Override
        protected void setRelation(Object vo, Map<?, ?> relationMap, Object source, AssemblyContext context) {
            setRelation(vo, relationMap, source);
        }
    }

    /**
     * 嵌套关联对象配置（基于 List 查询）
     * <p>
     * 适用于 MyBatis Plus 等批量查询方法接受 List 参数的场景。
     *
     * @param <I> ID 类型
     * @param <R> 关联对象类型
     * @param <V> 转换后的 VO 类型
     */
    @SuppressWarnings("rawtypes")
    private static class NestedRelationConfigWithList<I, R, V> extends NestedRelationConfig<I, R, V> {
        private final Function<List<I>, List<R>> queryFunctionWithList;

        NestedRelationConfigWithList(
                Function extractor,
                Function<List<I>, List<R>> queryFunctionWithList,
                Function relationIdGetter,
                Function<R, V> converter,
                BiConsumer voSetter,
                List<BaseRelationConfig<?, ?>> childRelations,
                List<NestedRelationConfig<?, ?, ?>> childNestedRelations,
                boolean parallel,
                Executor executor) {
            super(extractor, null, relationIdGetter, converter, voSetter,
                    childRelations, childNestedRelations, parallel, executor);
            this.queryFunctionWithList = queryFunctionWithList;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Map<I, ?> queryAndBuildMap(List<?> sourceList, AssemblyContext context, int batchSize) {
            int size = sourceList.size();
            Set<I> seen = new HashSet<>(size * 4 / 3 + 1);
            List<I> idList = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                I id = (I) extractor.apply(sourceList.get(i));
                if (id != null && seen.add(id)) {
                    idList.add(id);
                }
            }

            if (idList.isEmpty()) {
                return Collections.emptyMap();
            }

            if (log.isTraceEnabled()) {
                log.trace("[RelationAssembler] [query] 提取ID详情(List): 唯一ID数={}, IDs={}", idList.size(), formatIds(idList));
            }

            List<R> relations;
            if (batchSize > 0 && idList.size() > batchSize) {
                relations = batchQueryList(idList, batchSize);
            } else {
                log.debug("[RelationAssembler] [query] 提取到{}个唯一ID(List), 一次查询", idList.size());
                long start = System.nanoTime();
                relations = queryFunctionWithList.apply(idList);
                long time = (System.nanoTime() - start) / 1_000_000;
                log.debug("[RelationAssembler] [query] 查询完成(List): 耗时={}ms, 返回{}条结果", time, relations.size());
            }

            return buildResultMap(relations);
        }

        private List<R> batchQueryList(List<I> idList, int batchSize) {
            int totalBatches = (idList.size() + batchSize - 1) / batchSize;
            List<R> allRelations = new ArrayList<>(idList.size());
            long totalQueryTime = 0;
            for (int i = 0; i < idList.size(); i += batchSize) {
                int batchNum = i / batchSize + 1;
                int end = Math.min(i + batchSize, idList.size());
                List<I> batchIds = idList.subList(i, end);
                long start = System.nanoTime();
                List<R> batchResult = queryFunctionWithList.apply(batchIds);
                long batchTime = (System.nanoTime() - start) / 1_000_000;
                totalQueryTime += batchTime;
                allRelations.addAll(batchResult);
                log.trace("[RelationAssembler] [query] 批次{}/{}: {}个ID, 耗时={}ms, 返回{}条",
                        batchNum, totalBatches, batchIds.size(), batchTime, batchResult.size());
            }
            log.debug("[RelationAssembler] [query] 分批查询完成: {}批, 总耗时={}ms, 总结果={}条", totalBatches, totalQueryTime, allRelations.size());
            return allRelations;
        }
    }
}
