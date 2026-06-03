package io.github.lookfukc.non1.core;

import io.github.lookfukc.non1.copier.BeanCopier;
import io.github.lookfukc.non1.copier.DefaultBeanCopier;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 树形结构构建器
 * <p>
 * 用于将扁平的数据列表转换为树形结构，支持部门树、分类树、菜单树等场景。
 * <p>
 * 核心思想：通过 ID 和 parentId 的关联关系，将扁平列表组装成父子嵌套的树形结构。
 * 支持排序、过滤、层级深度、叶子节点标记、路径信息等扩展功能。
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 使用默认属性复制器（源和 VO 字段名一致时）
 * List<DepartmentVO> tree = TreeBuilder.from(departments, DepartmentVO.class)
 *     .idExtractor(Department::getId)
 *     .parentIdExtractor(Department::getParentId)
 *     .childrenSetter(DepartmentVO::setChildren)
 *     .build();
 *
 * // 使用 MapStruct 转换器
 * List<DepartmentVO> tree = TreeBuilder.from(departments, DepartmentVO.class, converter::toVO)
 *     .idExtractor(Department::getId)
 *     .parentIdExtractor(Department::getParentId)
 *     .childrenSetter(DepartmentVO::setChildren)
 *     .build();
 *
 * // 带排序和过滤
 * List<DepartmentVO> tree = TreeBuilder.from(departments, DepartmentVO.class, converter::toVO)
 *     .idExtractor(Department::getId)
 *     .parentIdExtractor(Department::getParentId)
 *     .childrenSetter(DepartmentVO::setChildren)
 *     .nodeComparator(Comparator.comparing(Department::getSort))
 *     .nodeFilter(dept -> dept.getStatus() == 1)
 *     .build();
 *
 * // 指定根节点
 * DepartmentVO tree = TreeBuilder.from(departments, DepartmentVO.class, converter::toVO)
 *     .idExtractor(Department::getId)
 *     .parentIdExtractor(Department::getParentId)
 *     .childrenSetter(DepartmentVO::setChildren)
 *     .rootId(1L)
 *     .buildSingle();
 *
 * // 带层级和路径信息
 * List<DepartmentVO> tree = TreeBuilder.from(departments, DepartmentVO.class, converter::toVO)
 *     .idExtractor(Department::getId)
 *     .parentIdExtractor(Department::getParentId)
 *     .childrenSetter(DepartmentVO::setChildren)
 *     .levelSetter((vo, level) -> vo.setLevel(level))
 *     .leafSetter((vo, isLeaf) -> vo.setIsLeaf(isLeaf))
 *     .pathSetter((vo, path) -> vo.setPath(String.join("/", path)))
 *     .build();
 * }</pre>
 *
 * @param <S> 源实体类型
 * @param <T> 目标 VO 类型
 * @param <I> ID 类型（通常为 Long）
 * @author lookfukc
 */
public class TreeBuilder<S, T, I> {

    /** VO 类构造器缓存 */
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    /** 源实体列表 */
    private final List<S> sourceList;
    /** 目标 VO 类型 */
    private final Class<T> voClass;
    /** 属性复制器 */
    private final BeanCopier<S, T> copier;
    /** VO 实例供应器（带构造器缓存） */
    private final Supplier<T> voSupplier;
    /** ID → VO 的映射，用于快速查找节点 */
    private final Map<I, T> voMap;
    /** 记录所有显式标记为根节点的 ID（parentId 为 null 的节点） */
    private final Set<I> rootIds;
    /** parentId → 子节点列表的映射 */
    private final Map<I, List<T>> childrenMap;
    /** VO 实例 → ID 的反向映射（使用 IdentityHashMap，基于引用比较） */
    private final IdentityHashMap<T, I> reverseVoMap;

    /** 从源实体提取 ID 的函数，如 {@code Department::getId} */
    private Function<S, I> idExtractor;
    /** 从源实体提取父 ID 的函数，如 {@code Department::getParentId} */
    private Function<S, I> parentIdExtractor;
    /** 转换前清空子节点的函数，用于避免脏数据 */
    private Consumer<T> childrenCleaner;
    /** 设置子节点到 VO 的函数，如 {@code DepartmentVO::setChildren} */
    private BiConsumerType<T, List<T>> childrenSetter;
    /** 节点过滤条件，只保留满足条件的节点 */
    private Predicate<S> nodeFilter;
    /** 节点排序规则，如 {@code Comparator.comparing(Department::getSort)} */
    private Comparator<T> nodeComparator;
    /** 指定根节点 ID，构建以该节点为根的子树 */
    private I rootId;
    /** 层级深度设置器，接收 VO 和层级深度（0, 1, 2...） */
    private BiConsumerType<T, Integer> levelSetter;
    /** 叶子节点标记设置器，接收 VO 和是否为叶子节点 */
    private BiConsumerType<T, Boolean> leafSetter;
    /** 路径设置器，接收 VO 和从根到当前节点的路径列表 */
    private BiConsumerType2<T, List<T>> pathSetter;

    /**
     * 使用自定义属性复制器创建构建器
     *
     * @param sourceList 源数据列表
     * @param voClass    目标 VO 类型，必须有公开的无参构造函数
     * @param copier     属性复制器实现，如 {@link io.github.lookfukc.non1.copier.SpringBeanUtilsCopier}、
     *                   {@link io.github.lookfukc.non1.copier.HutoolBeanCopier}、
     *                   {@link io.github.lookfukc.non1.copier.JdkBeansCopier} 等
     */
    @SuppressWarnings("unchecked")
    private TreeBuilder(List<S> sourceList, Class<T> voClass, BeanCopier<S, T> copier) {
        this.sourceList = sourceList;
        this.voClass = voClass;
        this.copier = copier;
        this.voSupplier = createVoSupplier(resolveConstructor(voClass)); // 缓存构造器，避免每次反射
        this.voMap = new HashMap<>();          // ID → VO 映射，用于快速查找节点
        this.rootIds = new HashSet<>();        // 记录 parentId 为 null 的根节点 ID
        this.childrenMap = new HashMap<>();    // parentId → 子节点列表映射
        this.reverseVoMap = new IdentityHashMap<>(); // VO → ID 反向映射，O(1) 查找
    }

    /**
     * 适配 Function 到 BeanCopier 接口的构造器
     * <p>
     * 将 MapStruct 的转换函数适配为 {@link BeanCopier} 接口，忽略 targetSupplier 参数。
     *
     * @param sourceList 源数据列表
     * @param voClass    目标 VO 类型，必须有公开的无参构造函数
     * @param converter  转换函数，通常是 MapStruct 生成的映射方法，如 {@code departmentMapper::toVO}
     */
    private TreeBuilder(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
        this.sourceList = sourceList;
        this.voClass = voClass;
        this.copier = (source, targetSupplier) -> converter.apply(source);
        this.voSupplier = createVoSupplier(resolveConstructor(voClass)); // 缓存构造器，避免每次反射
        this.voMap = new HashMap<>();          // ID → VO 映射，用于快速查找节点
        this.rootIds = new HashSet<>();        // 记录 parentId 为 null 的根节点 ID
        this.childrenMap = new HashMap<>();    // parentId → 子节点列表映射
        this.reverseVoMap = new IdentityHashMap<>(); // VO → ID 反向映射，O(1) 查找
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
     * 创建构建器，使用默认属性复制器
     * <p>
     * 默认使用 {@link DefaultBeanCopier}，基于反射和字段缓存实现属性复制。
     * 适用于源实体和 VO 字段名、类型一致的场景。
     * <p>
     * 如果源列表为空或 null，返回空构建器（{@link EmptyTreeBuilder}）。
     *
     * @param sourceList 源数据列表
     * @param voClass    目标 VO 类型
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    @SuppressWarnings("unchecked")
    public static <S, T> TreeBuilder<S, T, Object> from(List<S> sourceList, Class<T> voClass) {
        if (sourceList == null || sourceList.isEmpty()) {
            return new EmptyTreeBuilder<>(sourceList, voClass, (BeanCopier<S, T>) DefaultBeanCopier.INSTANCE);
        }
        return new TreeBuilder<>(sourceList, voClass, (BeanCopier<S, T>) DefaultBeanCopier.INSTANCE);
    }

    /**
     * 创建构建器，使用自定义转换函数
     * <p>
     * 适用于使用 MapStruct 或自定义 Lambda 表达式的场景。
     * 如果源列表为空或 null，返回空构建器（{@link EmptyTreeBuilder}）。
     *
     * @param sourceList 源数据列表
     * @param voClass    目标 VO 类型
     * @param converter  转换函数，通常是 MapStruct 的映射方法，如 {@code departmentMapper::toVO}
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    public static <S, T> TreeBuilder<S, T, Object> from(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
        if (sourceList == null || sourceList.isEmpty()) {
            return new EmptyTreeBuilder<>(sourceList, voClass, converter);
        }
        return new TreeBuilder<>(sourceList, voClass, converter);
    }

    /**
     * 创建构建器，使用自定义属性复制器
     * <p>
     * 当需要完全控制属性复制逻辑时使用此方法。
     * 如果源列表为空或 null，返回空构建器（{@link EmptyTreeBuilder}）。
     *
     * @param sourceList 源数据列表
     * @param voClass    目标 VO 类型
     * @param copier     属性复制器实现
     * @param <S>        源实体类型
     * @param <T>        目标 VO 类型
     * @return 构建器实例
     */
    public static <S, T> TreeBuilder<S, T, Object> from(List<S> sourceList, Class<T> voClass, BeanCopier<S, T> copier) {
        if (sourceList == null || sourceList.isEmpty()) {
            return new EmptyTreeBuilder<>(sourceList, voClass, copier);
        }
        return new TreeBuilder<>(sourceList, voClass, copier);
    }

    /**
     * 设置 ID 提取器（必需）
     *
     * @param idExtractor 从源实体提取 ID 的函数，如 {@code Department::getId}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> idExtractor(Function<S, I> idExtractor) {
        this.idExtractor = idExtractor;
        return this;
    }

    /**
     * 设置父 ID 提取器（必需）
     *
     * @param parentIdExtractor 从源实体提取父 ID 的函数，如 {@code Department::getParentId}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> parentIdExtractor(Function<S, I> parentIdExtractor) {
        this.parentIdExtractor = parentIdExtractor;
        return this;
    }

    /**
     * 设置子节点设置器（必需）
     *
     * @param childrenSetter 将子节点列表设置到 VO 的函数，如 {@code DepartmentVO::setChildren}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> childrenSetter(BiConsumerType<T, List<T>> childrenSetter) {
        this.childrenSetter = childrenSetter;
        return this;
    }

    /**
     * 设置子节点清理器（可选）
     * <p>
     * 在转换 VO 后、构建树之前调用，用于清空可能残留的子节点数据。
     *
     * @param childrenCleaner 清空子节点的函数，如 {@code DepartmentVO::clearChildren}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> childrenCleaner(Consumer<T> childrenCleaner) {
        this.childrenCleaner = childrenCleaner;
        return this;
    }

    /**
     * 设置节点过滤器（可选）
     * <p>
     * 只保留满足条件的源实体参与构建树，不满足条件的节点及其子树将被排除。
     *
     * @param nodeFilter 节点过滤条件，如 {@code dept -> dept.getStatus() == 1}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> nodeFilter(Predicate<S> nodeFilter) {
        this.nodeFilter = nodeFilter;
        return this;
    }

    /**
     * 设置节点排序规则（可选）
     * <p>
     * 对同一层级的子节点进行排序。
     *
     * @param nodeComparator 节点比较器，如 {@code Comparator.comparing(Department::getSort)}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> nodeComparator(Comparator<T> nodeComparator) {
        this.nodeComparator = nodeComparator;
        return this;
    }

    /**
     * 设置根节点 ID（可选）
     * <p>
     * 指定根节点后，只构建以该节点为根的子树，通常配合 {@link #buildSingle()} 使用。
     *
     * @param rootId 根节点 ID
     * @return 当前构建器，用于链式调用
     */
    @SuppressWarnings("unchecked")
    public TreeBuilder<S, T, I> rootId(I rootId) {
        this.rootId = rootId;
        return this;
    }

    /**
     * 设置层级深度设置器（可选）
     * <p>
     * 为每个节点设置层级深度，根节点为 0，每深入一层加 1。
     *
     * @param levelSetter 层级设置函数，接收 VO 和层级深度，如 {@code (vo, level) -> vo.setLevel(level)}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> levelSetter(BiConsumerType<T, Integer> levelSetter) {
        this.levelSetter = levelSetter;
        return this;
    }

    /**
     * 设置叶子节点标记设置器（可选）
     * <p>
     * 为每个节点标记是否为叶子节点（没有子节点的节点）。
     *
     * @param leafSetter 叶子节点标记函数，接收 VO 和是否为叶子节点，如 {@code (vo, isLeaf) -> vo.setIsLeaf(isLeaf)}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> leafSetter(BiConsumerType<T, Boolean> leafSetter) {
        this.leafSetter = leafSetter;
        return this;
    }

    /**
     * 设置路径设置器（可选）
     * <p>
     * 为每个节点设置从根到当前节点的路径列表（包含自身）。
     *
     * @param pathSetter 路径设置函数，接收 VO 和路径节点列表，如 {@code (vo, path) -> vo.setPath(joinNames(path))}
     * @return 当前构建器，用于链式调用
     */
    public TreeBuilder<S, T, I> pathSetter(BiConsumerType2<T, List<T>> pathSetter) {
        this.pathSetter = pathSetter;
        return this;
    }

    /**
     * 构建树形结构，返回所有根节点的列表
     * <p>
     * 自动查找 parentId 为 null 或 parentId 不在当前列表中的节点作为根节点。
     *
     * @return 树形结构的根节点列表
     * @throws IllegalArgumentException 如果未设置 idExtractor、parentIdExtractor 或 childrenSetter
     */
    public List<T> build() {
        return buildTree();
    }

    /**
     * 构建树形结构，返回单个根节点
     * <p>
     * 适用于配合 {@link #rootId(Object)} 指定根节点后获取单个子树的场景。
     *
     * @return 树形结构的根节点，如果没有找到根节点返回 null
     * @throws RuntimeException 如果找到多个根节点
     */
    public T buildSingle() {
        List<T> roots = buildTree();
        if (roots.isEmpty()) {
            return null;
        }
        if (roots.size() > 1) {
            throw new RuntimeException("找到多个根节点，请使用 build() 方法或指定 rootId");
        }
        return roots.get(0);
    }

    /**
     * 构建树形结构的核心逻辑
     * <p>
     * 执行流程：
     * <ol>
     *   <li>参数校验（idExtractor、parentIdExtractor、childrenSetter 必须设置）</li>
     *   <li>遍历源列表，转换 VO 并构建 id→vo、parentId→children 映射</li>
     *   <li>确定根节点（parentId 为 null 或不在当前列表中的节点）</li>
     *   <li>递归构建每个根节点的子树</li>
     * </ol>
     *
     * @return 树形结构的根节点列表
     */
    @SuppressWarnings("unchecked")
    private List<T> buildTree() {
        if (idExtractor == null) {
            throw new IllegalArgumentException("必须设置 idExtractor");
        }
        if (parentIdExtractor == null) {
            throw new IllegalArgumentException("必须设置 parentIdExtractor");
        }
        if (childrenSetter == null) {
            throw new IllegalArgumentException("必须设置 childrenSetter");
        }

        int size = sourceList.size();
        Map<I, I> idToParentId = new HashMap<>(size * 4 / 3 + 1);

        // 第一遍遍历：转换 VO，构建映射关系
        for (S source : sourceList) {
            if (nodeFilter != null && !nodeFilter.test(source)) {
                continue;
            }

            I id = idExtractor.apply(source);
            I parentId = parentIdExtractor.apply(source);
            T vo = copier.copy(source, voSupplier);

            if (childrenCleaner != null) {
                childrenCleaner.accept(vo);
            }

            voMap.put(id, vo);
            reverseVoMap.put(vo, id);
            idToParentId.put(id, parentId);

            childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(vo);

            if (parentId == null) {
                rootIds.add(id);
            }
        }

        // 确定根节点
        Set<I> actualRootIds;
        if (rootId != null) {
            // 使用指定的根节点
            actualRootIds = Collections.singleton(rootId);
        } else {
            // 查找父 ID 不在当前列表中的节点作为根节点
            actualRootIds = new HashSet<>();
            for (I id : voMap.keySet()) {
                I parentId = idToParentId.get(id);
                if (parentId == null || !voMap.containsKey(parentId)) {
                    actualRootIds.add(id);
                }
            }
        }

        // 递归构建树形结构
        List<T> roots = new ArrayList<>(actualRootIds.size());
        List<T> sharedPath = new ArrayList<>();
        for (I rid : actualRootIds) {
            T rootVo = voMap.get(rid);
            if (rootVo != null) {
                buildNode(rootVo, 0, sharedPath);
                roots.add(rootVo);
            }
        }

        return roots;
    }

    /**
     * 递归构建节点，设置层级、路径、子节点等信息
     *
     * @param vo    当前节点 VO
     * @param level 当前层级深度（根节点为 0）
     * @param path  共享的路径列表（从根到当前节点的路径，递归过程中复用）
     */
    @SuppressWarnings("unchecked")
    private void buildNode(T vo, int level, List<T> path) {
        I id = reverseVoMap.get(vo);
        if (id == null) {
            return;
        }

        // 设置层级深度
        if (levelSetter != null) {
            levelSetter.accept(vo, level);
        }

        path.add(vo);

        // 设置路径
        if (pathSetter != null) {
            pathSetter.accept(vo, new ArrayList<>(path));
        }

        // 获取子节点
        List<T> children = childrenMap.get(id);

        // 设置叶子节点标记
        if (leafSetter != null) {
            leafSetter.accept(vo, children == null || children.isEmpty());
        }

        if (children != null && !children.isEmpty()) {
            // 排序
            if (nodeComparator != null) {
                children.sort(nodeComparator);
            }
            // 设置子节点
            childrenSetter.accept(vo, children);
            // 递归构建子节点
            for (T child : children) {
                buildNode(child, level + 1, path);
            }
        } else {
            // 叶子节点设置空的子节点列表
            childrenSetter.accept(vo, Collections.emptyList());
        }

        path.remove(path.size() - 1);
    }

    /**
     * BiConsumer 类型适配器（两个参数）
     *
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     */
    @FunctionalInterface
    public interface BiConsumerType<T, U> {
        /**
         * 接受两个参数并执行操作
         *
         * @param t 第一个参数
         * @param u 第二个参数
         */
        void accept(T t, U u);
    }

    /**
     * BiConsumer 类型适配器（用于路径设置等场景）
     *
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     */
    @FunctionalInterface
    public interface BiConsumerType2<T, U> {
        /**
         * 接受两个参数并执行操作
         *
         * @param t 第一个参数
         * @param u 第二个参数
         */
        void accept(T t, U u);
    }

    /**
     * 空构建器（用于空列表的快速返回）
     * <p>
     * 当源列表为空时，直接返回空结果，避免不必要的对象创建和逻辑处理。
     *
     * @param <S> 源实体类型
     * @param <T> 目标 VO 类型
     * @param <I> ID 类型
     */
    private static class EmptyTreeBuilder<S, T, I> extends TreeBuilder<S, T, I> {
        private EmptyTreeBuilder(List<S> sourceList, Class<T> voClass, BeanCopier<S, T> copier) {
            super(sourceList, voClass, copier);
        }

        private EmptyTreeBuilder(List<S> sourceList, Class<T> voClass, Function<S, T> converter) {
            super(sourceList, voClass, converter);
        }

        @Override
        public List<T> build() {
            return Collections.emptyList();
        }

        @Override
        public T buildSingle() {
            return null;
        }
    }

}
