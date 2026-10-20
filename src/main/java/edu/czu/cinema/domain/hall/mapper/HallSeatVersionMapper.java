package edu.czu.cinema.domain.hall.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.hall.entity.HallSeatVersion;

import java.util.List;

public interface HallSeatVersionMapper extends BaseMapper<HallSeatVersion> {

    /** 查某厅当前生效的版本. 找不到返回 null(调用方自己处理). */
    default HallSeatVersion findCurrentByHall(Long hallId) {
        return selectOne(new LambdaQueryWrapper<HallSeatVersion>()
                .eq(HallSeatVersion::getHallId, hallId)
                .eq(HallSeatVersion::getIsCurrent, 1)
                .last("LIMIT 1"));
    }

    /** 查某厅指定版本号的快照. 找不到返回 null. */
    default HallSeatVersion findByHallAndVersion(Long hallId, Integer version) {
        return selectOne(new LambdaQueryWrapper<HallSeatVersion>()
                .eq(HallSeatVersion::getHallId, hallId)
                .eq(HallSeatVersion::getVersion, version)
                .last("LIMIT 1"));
    }

    /** 查某厅所有版本, 按 version 降序(新→旧). */
    default List<HallSeatVersion> listByHall(Long hallId) {
        return selectList(new LambdaQueryWrapper<HallSeatVersion>()
                .eq(HallSeatVersion::getHallId, hallId)
                .orderByDesc(HallSeatVersion::getVersion));
    }

    /**
     * 原子地「读草稿 + 校验 draft_lock_version 匹配 + +1 写回」. 不匹配返回 0 行.
     * 用法: int updated = tryLockDraft(draftId, expectedVersion); updated==0 说明被别人改了.
     */
    default int tryLockDraft(Long draftId, Integer expectedVersion) {
        // CAS：WHERE 带期望版本，SET 为期望值+1；两个并发只有一个能命中。
        return update(null, new LambdaUpdateWrapper<HallSeatVersion>()
                .set(HallSeatVersion::getDraftLockVersion, expectedVersion + 1)
                .eq(HallSeatVersion::getId, draftId)
                .eq(HallSeatVersion::getDraftLockVersion, expectedVersion));
    }
}
