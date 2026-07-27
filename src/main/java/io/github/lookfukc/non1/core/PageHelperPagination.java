package io.github.lookfukc.non1.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * PageHelper 分页信息透传工具
 * <p>
 * PageHelper 把分页总数 {@code total} 绑定在 {@code com.github.pagehelper.Page}
 * （继承自 {@link ArrayList}）上。当源列表经过转换被重建为普通 {@code ArrayList} 时，
 * total 会随之丢失，导致上层 {@code new PageInfo(list).getTotal()} 回退为
 * {@code list.size()}，从而出现「总数等于当前页条数」的问题。
 * <p>
 * 本工具通过反射（零硬依赖）在转换前后捕获并重应用 total，避免该问题。
 * 当 classpath 中不存在 PageHelper 时，{@link #captureTotal(List)} 恒返回 {@code null}，
 * {@link #applyTotal(Long, List)} 恒原样返回结果，无任何副作用与性能损耗。
 *
 * @author lookfukc
 */
final class PageHelperPagination {

    private static final String PAGE_CLASS_NAME = "com.github.pagehelper.Page";

    /** PageHelper 的 Page 类，classpath 中不存在时为 null */
    private static final Class<?> PAGE_CLASS;
    /** Page 无参构造器，用于构造承载 total 的返回容器 */
    private static final Constructor<?> PAGE_CTOR;
    /** Page.getTotal() */
    private static final Method GET_TOTAL;
    /** Page.setTotal(long) */
    private static final Method SET_TOTAL;

    static {
        Class<?> pageClass = null;
        Constructor<?> ctor = null;
        Method getTotal = null;
        Method setTotal = null;
        try {
            pageClass = Class.forName(PAGE_CLASS_NAME);
            ctor = pageClass.getConstructor();
            getTotal = pageClass.getMethod("getTotal");
            setTotal = pageClass.getMethod("setTotal", long.class);
        } catch (Throwable e) {
            // classpath 中不存在 PageHelper，保持 no-op
        }
        PAGE_CLASS = pageClass;
        PAGE_CTOR = ctor;
        GET_TOTAL = getTotal;
        SET_TOTAL = setTotal;
    }

    private PageHelperPagination() {
    }

    /**
     * 捕获源列表上的 PageHelper 分页总数。
     * <p>
     * 若 classpath 无 PageHelper、或 sourceList 不是 {@code com.github.pagehelper.Page}，
     * 返回 {@code null}（表示非 PageHelper 场景，无需透传）。
     *
     * @param sourceList 源列表
     * @return total 值；非 PageHelper 场景返回 {@code null}
     */
    static Long captureTotal(List<?> sourceList) {
        if (PAGE_CLASS == null || sourceList == null || !PAGE_CLASS.isInstance(sourceList)) {
            return null;
        }
        try {
            return (Long) GET_TOTAL.invoke(sourceList);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 将捕获的 total 重应用到结果列表，构造一个新的 PageHelper {@code Page} 容器。
     * <p>
     * 若 capturedTotal 为 {@code null}（非 PageHelper 场景）或反射构造失败，
     * 原样返回 result，不做任何处理。
     *
     * @param capturedTotal {@link #captureTotal(List)} 捕获到的 total，可为 {@code null}
     * @param result        转换后的结果列表
     * @param <T>           VO 类型
     * @return 带有 total 的 PageHelper Page（作为 List 返回）；或原 result
     */
    @SuppressWarnings("unchecked")
    static <T> List<T> applyTotal(Long capturedTotal, List<T> result) {
        if (capturedTotal == null || PAGE_CLASS == null) {
            return result;
        }
        try {
            List<Object> page = (List<Object>) PAGE_CTOR.newInstance();
            page.addAll(result);
            SET_TOTAL.invoke(page, capturedTotal);
            return (List<T>) page;
        } catch (Throwable e) {
            return result;
        }
    }
}
