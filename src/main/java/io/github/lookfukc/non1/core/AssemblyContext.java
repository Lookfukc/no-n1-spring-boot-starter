package io.github.lookfukc.non1.core;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 组装上下文
 * <p>
 * 用于在关联组装过程中传递共享数据，支持多层嵌套组装场景。
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 预先加载数据到 Context
 * Map<Long, Workshift> workshiftMap = workshiftMapper.selectBatchIds(ids);
 *
 * RelationAssembler.from(list, ProductionScheduleVO.class, converter::toVO)
 *     .withSharedData("workshiftMap", workshiftMap)
 *     .withRelation(
 *         ProductionSchedule::getWorkshiftGroupId,
 *         ids -> workshiftGroupMapper.selectByIds(ids),
 *         WorkshiftGroup::getWorkshiftGroupId,
 *         (wg, context) -> {
 *             // 从 Context 获取预加载的数据
 *             Map<Long, Workshift> map = context.getShared("workshiftMap");
 *             WorkshiftGroupVO vo = converter.toVO(wg);
 *             vo.setDayWorkshiftEntity(map.get(wg.getDayWorkshiftId()));
 *             vo.setNightWorkshiftEntity(map.get(wg.getNightWorkshiftId()));
 *             return vo;
 *         },
 *         ProductionScheduleVO::setWorkshiftGroup
 *     )
 *     .build();
 * }</pre>
 *
 * @author lookfukc
 */
public class AssemblyContext {

    /**
     * 共享数据 Map
     */
    private final Map<String, Object> sharedData;

    /**
     * 并行查询执行器
     */
    private final Executor executor;

    /**
     * 当前组装的源对象列表（用于嵌套场景）
     */
    private final Object currentSourceList;

    public AssemblyContext(Map<String, Object> sharedData, Executor executor, Object currentSourceList) {
        this.sharedData = sharedData;
        this.executor = executor;
        this.currentSourceList = currentSourceList;
    }

    /**
     * 获取共享数据
     *
     * @param key 数据键
     * @param <T> 数据类型
     * @return 共享数据，如果不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getShared(String key) {
        return (T) sharedData.get(key);
    }

    /**
     * 获取共享数据，如果不存在返回默认值
     *
     * @param key          数据键
     * @param defaultValue 默认值
     * @param <T>          数据类型
     * @return 共享数据或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getShared(String key, T defaultValue) {
        Object value = sharedData.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * 设置共享数据
     *
     * @param key   数据键
     * @param value 数据值
     */
    public void setShared(String key, Object value) {
        sharedData.put(key, value);
    }

    /**
     * 获取共享数据 Map
     *
     * @return 共享数据 Map
     */
    public Map<String, Object> getSharedData() {
        return sharedData;
    }

    /**
     * 获取并行查询执行器
     *
     * @return Executor
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * 获取当前组装的源对象列表
     *
     * @param <S> 源对象类型
     * @return 源对象列表
     */
    @SuppressWarnings("unchecked")
    public <S> S getCurrentSourceList() {
        return (S) currentSourceList;
    }
}
