package edu.czu.cinema.domain.hall.service;

import edu.czu.cinema.domain.hall.dto.SeatTemplateCreateDTO;
import edu.czu.cinema.domain.hall.dto.SeatTemplateReplaceUnitsDTO;
import edu.czu.cinema.domain.hall.entity.SeatTemplate;

import java.util.List;

public interface SeatTemplateService {

    /** 模板列表(带 unit count). 管理员页面用. */
    List<SeatTemplate> listTemplates();

    /** 模板详情(含 units). 供编辑器 / 「从模板建影厅」用. */
    SeatTemplate getById(Long id);

    /**
     * 新建模板. 同时写入 units; 不传 units 时按 rows×cols 全 status=1.
     *
     * @return 新模板 id
     */
    Long create(SeatTemplateCreateDTO dto);

    /**
     * 「整图覆盖」替换模板的单元. 整事务内 delete + batch insert.
     * 模板本身的名字/行列数不能改 —— 改行列数会让引用它的影厅布局不一致,
     * 那些场景留到 Phase 3 走「草稿/发布」路径.
     */
    void replaceUnits(Long templateId, SeatTemplateReplaceUnitsDTO dto);

    /**
     * 删除模板. 后续 Phase 2/3 在有影厅引用时拒绝; Phase 1 没有引用关系, 直接物理删.
     * Phase 2+ 改为校验后拒绝.
     */
    void delete(Long templateId);
}
