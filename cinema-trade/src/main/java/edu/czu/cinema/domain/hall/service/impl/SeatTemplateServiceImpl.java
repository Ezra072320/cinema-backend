package edu.czu.cinema.domain.hall.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.dto.SeatTemplateCreateDTO;
import edu.czu.cinema.domain.hall.dto.SeatTemplateReplaceUnitsDTO;
import edu.czu.cinema.domain.hall.dto.SeatTemplateUnitDTO;
import edu.czu.cinema.domain.hall.entity.SeatTemplate;
import edu.czu.cinema.domain.hall.entity.SeatTemplateUnit;
import edu.czu.cinema.domain.hall.mapper.SeatTemplateMapper;
import edu.czu.cinema.domain.hall.mapper.SeatTemplateUnitMapper;
import edu.czu.cinema.domain.hall.service.SeatTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatTemplateServiceImpl implements SeatTemplateService {

    private final SeatTemplateMapper templateMapper;
    private final SeatTemplateUnitMapper unitMapper;

    @Override
    public List<SeatTemplate> listTemplates() {
        // 一条 SQL 把 unitCount 算出来, 避免列表 N+1
        return templateMapper.selectListWithCount();
    }

    @Override
    public SeatTemplate getById(Long id) {
        SeatTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模板不存在");
        }
        t.setUnits(unitMapper.listByTemplate(id));
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SeatTemplateCreateDTO dto) {
        // 1. 重名校验 —— UNIQUE 兜底, 但提前 catch 能给前端更明确的提示
        Long exists = templateMapper.selectCount(new LambdaQueryWrapper<SeatTemplate>()
                .eq(SeatTemplate::getName, dto.getName()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "模板名称已存在");
        }
        // 2. 写主表
        SeatTemplate t = new SeatTemplate();
        t.setName(dto.getName());
        t.setDescription(dto.getDescription());
        t.setRowsCount(dto.getRowsCount());
        t.setColsCount(dto.getColsCount());
        try {
            templateMapper.insert(t);
        } catch (DuplicateKeyException e) {
            // 并发场景兜底: 两人同时新建同名
            throw new BusinessException(ResultCode.CONFLICT, "模板名称已存在");
        }
        // 3. 生成 units
        List<SeatTemplateUnit> units = buildUnits(t.getId(), dto.getRowsCount(), dto.getColsCount(), dto.getUnits());
        if (CollUtil.isNotEmpty(units)) {
            unitMapper.batchInsert(units);
        }
        log.info("[seat-template] 新建模板 id={} name={} {}x{} units={}",
                t.getId(), t.getName(), t.getRowsCount(), t.getColsCount(), units.size());
        return t.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceUnits(Long templateId, SeatTemplateReplaceUnitsDTO dto) {
        SeatTemplate t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模板不存在");
        }
        // 1. 校验: 传入的 units 必须在模板的 (rows, cols) 范围内
        validateUnitsWithinBounds(t, dto.getUnits());
        // 2. 整图覆盖: delete + insert, 同一事务
        unitMapper.deleteByTemplate(templateId);
        List<SeatTemplateUnit> units = toEntities(templateId, dto.getUnits());
        if (CollUtil.isNotEmpty(units)) {
            unitMapper.batchInsert(units);
        }
        log.info("[seat-template] 替换模板单元 templateId={} 新单元数={}", templateId, units.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long templateId) {
        SeatTemplate t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模板不存在");
        }
        // 先删 units, 再删主表 —— FK 没建 ON DELETE CASCADE, 手工级联
        unitMapper.deleteByTemplate(templateId);
        templateMapper.deleteById(templateId);
        log.info("[seat-template] 删除模板 id={} name={}", t.getId(), t.getName());
    }

    /* ====================== helpers ====================== */

    /**
     * 把 DTO 转成 entity 列表. 校验: (y, x) 不能重复; 必须 1-based.
     * 校验在前端 + 后端都做, 减少「前端漏校验」事故.
     */
    private List<SeatTemplateUnit> toEntities(Long templateId, List<SeatTemplateUnitDTO> dtos) {
        if (CollUtil.isEmpty(dtos)) return List.of();
        Set<String> seen = new HashSet<>();
        List<SeatTemplateUnit> out = new ArrayList<>(dtos.size());
        for (SeatTemplateUnitDTO d : dtos) {
            String key = d.getY() + "-" + d.getX();
            if (!seen.add(key)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "同一模板下 (y=" + d.getY() + ", x=" + d.getX() + ") 重复");
            }
            SeatTemplateUnit u = new SeatTemplateUnit();
            u.setTemplateId(templateId);
            u.setY(d.getY());
            u.setX(d.getX());
            u.setStatus(d.getStatus() == null ? 1 : d.getStatus());
            u.setLabel(StrUtil.isBlank(d.getLabel()) ? null : d.getLabel().trim());
            out.add(u);
        }
        return out;
    }

    /**
     * 校验传入的 units 都在 (rowsCount, colsCount) 范围内.
     * 越界会让前端的网格编辑器渲染错位, 所以这里直接拒.
     */
    private void validateUnitsWithinBounds(SeatTemplate t, List<SeatTemplateUnitDTO> units) {
        if (CollUtil.isEmpty(units)) return;
        for (SeatTemplateUnitDTO u : units) {
            if (u.getY() == null || u.getY() < 1 || u.getY() > t.getRowsCount()) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "y 越界: " + u.getY() + " (合法范围 1.." + t.getRowsCount() + ")");
            }
            if (u.getX() == null || u.getX() < 1 || u.getX() > t.getColsCount()) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "x 越界: " + u.getX() + " (合法范围 1.." + t.getColsCount() + ")");
            }
        }
    }

    /**
     * 新建时构造初始 units. dto.units 为空时按 rows×cols 全 status=1.
     * 传入时则把 dto 全部转成 entity, 但仍要校验在范围内.
     */
    private List<SeatTemplateUnit> buildUnits(Long templateId, Integer rows, Integer cols, List<SeatTemplateUnitDTO> incoming) {
        if (CollUtil.isEmpty(incoming)) {
            List<SeatTemplateUnit> all = new ArrayList<>(rows * cols);
            for (int y = 1; y <= rows; y++) {
                for (int x = 1; x <= cols; x++) {
                    SeatTemplateUnit u = new SeatTemplateUnit();
                    u.setTemplateId(templateId);
                    u.setY(y);
                    u.setX(x);
                    u.setStatus(1);
                    u.setLabel(null);
                    all.add(u);
                }
            }
            return all;
        }
        // 显式传入 units —— 用 (rows, cols) 临时构造一个伪 t 复用校验
        SeatTemplate pseudo = new SeatTemplate();
        pseudo.setRowsCount(rows);
        pseudo.setColsCount(cols);
        validateUnitsWithinBounds(pseudo, incoming);
        return toEntities(templateId, incoming);
    }
}
