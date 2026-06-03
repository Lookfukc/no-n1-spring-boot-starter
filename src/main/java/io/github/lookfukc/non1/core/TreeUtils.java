package io.github.lookfukc.non1.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 树工具类
 * <p>
 * 提供树形结构的常用操作方法，包括扁平化、搜索、深度计算、查找、路径获取等。
 * 默认通过反射获取名为 {@code children} 的 {@link List} 类型字段，
 * 也支持通过 {@code childrenFieldName} 参数指定自定义字段名。
 *
 * @author lookfukc
 */
public class TreeUtils {

    /** 字段缓存：className#fieldName → Field */
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private TreeUtils() {
    }

    /**
     * 获取指定字段（带缓存）
     *
     * @param clazz     目标类
     * @param fieldName 字段名
     * @return 字段，如果不存在返回 null
     */
    private static Field getField(Class<?> clazz, String fieldName) {
        return FIELD_CACHE.computeIfAbsent(clazz.getName() + '#' + fieldName, k -> {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                return null;
            }
        });
    }

    /**
     * 获取 children 字段（带缓存）
     */
    private static Field getChildrenField(Class<?> clazz) {
        return getField(clazz, "children");
    }

    // ==================== flatten ====================

    /**
     * 扁平化树形结构（默认 children 字段名）
     *
     * @param tree 树形结构的根节点列表
     * @param <T>  节点类型
     * @return 扁平化的节点列表（按层级遍历顺序）
     */
    public static <T> List<T> flatten(List<T> tree) {
        return flatten(tree, "children");
    }

    /**
     * 扁平化树形结构（自定义 children 字段名）
     *
     * @param tree              树形结构的根节点列表
     * @param childrenFieldName 子节点字段名
     * @param <T>               节点类型
     * @return 扁平化的节点列表（按层级遍历顺序）
     */
    public static <T> List<T> flatten(List<T> tree, String childrenFieldName) {
        if (tree.isEmpty()) {
            return new ArrayList<>();
        }
        Field field = getField(tree.get(0).getClass(), childrenFieldName);
        List<T> result = new ArrayList<>();
        for (T node : tree) {
            flattenNode(node, result, field);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> void flattenNode(T node, List<T> result, Field field) {
        result.add(node);
        if (field == null) {
            return;
        }
        try {
            Object children = field.get(node);
            if (children instanceof List) {
                for (Object child : (List<?>) children) {
                    flattenNode((T) child, result, field);
                }
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    // ==================== search ====================

    /**
     * 搜索树中所有满足条件的节点（默认 children 字段名）
     *
     * @param tree      树形结构的根节点列表
     * @param predicate 匹配条件
     * @param <T>       节点类型
     * @return 匹配的节点列表
     */
    public static <T> List<T> search(List<T> tree, Predicate<T> predicate) {
        return search(tree, predicate, "children");
    }

    /**
     * 搜索树中所有满足条件的节点（自定义 children 字段名）
     *
     * @param tree              树形结构的根节点列表
     * @param predicate         匹配条件
     * @param childrenFieldName 子节点字段名
     * @param <T>               节点类型
     * @return 匹配的节点列表
     */
    public static <T> List<T> search(List<T> tree, Predicate<T> predicate, String childrenFieldName) {
        if (tree.isEmpty()) {
            return new ArrayList<>();
        }
        Field field = getField(tree.get(0).getClass(), childrenFieldName);
        List<T> result = new ArrayList<>();
        for (T node : tree) {
            searchNode(node, predicate, result, field);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> void searchNode(T node, Predicate<T> predicate, List<T> result, Field field) {
        if (predicate.test(node)) {
            result.add(node);
        }
        if (field == null) {
            return;
        }
        try {
            Object children = field.get(node);
            if (children instanceof List) {
                for (Object child : (List<?>) children) {
                    searchNode((T) child, predicate, result, field);
                }
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    // ==================== findFirst ====================

    /**
     * 查找树中第一个满足条件的节点（深度优先，默认 children 字段名）
     *
     * @param tree      树形结构的根节点列表
     * @param predicate 匹配条件
     * @param <T>       节点类型
     * @return 第一个匹配的节点，未找到返回 null
     */
    public static <T> T findFirst(List<T> tree, Predicate<T> predicate) {
        return findFirst(tree, predicate, "children");
    }

    /**
     * 查找树中第一个满足条件的节点（深度优先，自定义 children 字段名）
     *
     * @param tree              树形结构的根节点列表
     * @param predicate         匹配条件
     * @param childrenFieldName 子节点字段名
     * @param <T>               节点类型
     * @return 第一个匹配的节点，未找到返回 null
     */
    public static <T> T findFirst(List<T> tree, Predicate<T> predicate, String childrenFieldName) {
        if (tree.isEmpty()) {
            return null;
        }
        Field field = getField(tree.get(0).getClass(), childrenFieldName);
        for (T node : tree) {
            T found = findFirstNode(node, predicate, field);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T findFirstNode(T node, Predicate<T> predicate, Field field) {
        if (predicate.test(node)) {
            return node;
        }
        if (field == null) {
            return null;
        }
        try {
            Object children = field.get(node);
            if (children instanceof List) {
                for (Object child : (List<?>) children) {
                    T found = findFirstNode((T) child, predicate, field);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (IllegalAccessException ignored) {
        }
        return null;
    }

    // ==================== getDepth ====================

    /**
     * 获取树的最大深度（默认 children 字段名）
     *
     * @param tree 树形结构的根节点列表
     * @param <T>  节点类型
     * @return 树的最大深度（只有一个根节点时返回 1）
     */
    public static <T> int getDepth(List<T> tree) {
        return getDepth(tree, "children");
    }

    /**
     * 获取树的最大深度（自定义 children 字段名）
     *
     * @param tree              树形结构的根节点列表
     * @param childrenFieldName 子节点字段名
     * @param <T>               节点类型
     * @return 树的最大深度
     */
    public static <T> int getDepth(List<T> tree, String childrenFieldName) {
        if (tree.isEmpty()) {
            return 0;
        }
        Field field = getField(tree.get(0).getClass(), childrenFieldName);
        int maxDepth = 0;
        for (T node : tree) {
            maxDepth = Math.max(maxDepth, getNodeDepth(node, 1, field));
        }
        return maxDepth;
    }

    @SuppressWarnings("unchecked")
    private static <T> int getNodeDepth(T node, int currentDepth, Field field) {
        if (field == null) {
            return currentDepth;
        }
        try {
            Object children = field.get(node);
            if (children instanceof List && !((List<?>) children).isEmpty()) {
                int maxChildDepth = currentDepth;
                for (Object child : (List<?>) children) {
                    maxChildDepth = Math.max(maxChildDepth, getNodeDepth((T) child, currentDepth + 1, field));
                }
                return maxChildDepth;
            }
        } catch (IllegalAccessException ignored) {
        }
        return currentDepth;
    }

    // ==================== countNodes ====================

    /**
     * 统计树节点总数（默认 children 字段名）
     *
     * @param tree 树形结构的根节点列表
     * @param <T>  节点类型
     * @return 树中所有节点总数
     */
    public static <T> int countNodes(List<T> tree) {
        return countNodes(tree, "children");
    }

    /**
     * 统计树节点总数（自定义 children 字段名）
     *
     * @param tree              树形结构的根节点列表
     * @param childrenFieldName 子节点字段名
     * @param <T>               节点类型
     * @return 树中所有节点总数
     */
    public static <T> int countNodes(List<T> tree, String childrenFieldName) {
        if (tree.isEmpty()) {
            return 0;
        }
        Field field = getField(tree.get(0).getClass(), childrenFieldName);
        int[] count = {0};
        countNodesInternal(tree, field, count);
        return count[0];
    }

    @SuppressWarnings("unchecked")
    private static <T> void countNodesInternal(List<T> nodes, Field field, int[] count) {
        for (T node : nodes) {
            count[0]++;
            if (field != null) {
                try {
                    Object children = field.get(node);
                    if (children instanceof List && !((List<?>) children).isEmpty()) {
                        countNodesInternal((List<T>) children, field, count);
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
    }

    // ==================== getParentPath ====================

    /**
     * 获取从根节点到目标节点的路径（默认 children 字段名）
     * <p>
     * 路径包含目标节点自身。例如查找 "Java组" 的路径返回：[研发部, 后端组, Java组]
     *
     * @param tree            树形结构的根节点列表
     * @param targetPredicate 目标节点匹配条件
     * @param <T>             节点类型
     * @return 从根到目标节点的路径列表，未找到返回空列表
     */
    public static <T> List<T> getParentPath(List<T> tree, Predicate<T> targetPredicate) {
        return getParentPath(tree, targetPredicate, "children");
    }

    /**
     * 获取从根节点到目标节点的路径（自定义 children 字段名）
     *
     * @param tree              树形结构的根节点列表
     * @param targetPredicate   目标节点匹配条件
     * @param childrenFieldName 子节点字段名
     * @param <T>               节点类型
     * @return 从根到目标节点的路径列表，未找到返回空列表
     */
    public static <T> List<T> getParentPath(List<T> tree, Predicate<T> targetPredicate, String childrenFieldName) {
        if (tree.isEmpty()) {
            return new ArrayList<>();
        }
        Field field = getField(tree.get(0).getClass(), childrenFieldName);
        List<T> path = new ArrayList<>();
        findPath(tree, targetPredicate, path, field);
        return path;
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean findPath(List<T> nodes, Predicate<T> target, List<T> path, Field field) {
        for (T node : nodes) {
            path.add(node);
            if (target.test(node)) {
                return true;
            }
            if (field != null) {
                try {
                    Object children = field.get(node);
                    if (children instanceof List && !((List<?>) children).isEmpty()) {
                        if (findPath((List<T>) children, target, path, field)) {
                            return true;
                        }
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
            path.remove(path.size() - 1);
        }
        return false;
    }
}
