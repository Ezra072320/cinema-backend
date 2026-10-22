package edu.czu.cinema.domain.admin.service;

import edu.czu.cinema.domain.admin.entity.AdminOperationLog;
import edu.czu.cinema.domain.admin.mapper.AdminOperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 管理端高危操作审计。失败不阻断业务，但正常路径必须留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminOperationLogMapper mapper;

    public void record(Long adminId, String adminName, String action,
                       String targetType, Long targetId, String detail) {
        try {
            AdminOperationLog entry = new AdminOperationLog();
            entry.setAdminId(adminId);
            entry.setAdminName(adminName);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detail);
            entry.setCreatedAt(LocalDateTime.now());
            mapper.insert(entry);
            log.info("管理操作审计 adminId={} action={} target={}/{} detail={}",
                    adminId, action, targetType, targetId, detail);
        } catch (Exception e) {
            log.error("管理操作审计落库失败 adminId={} action={} target={}/{}",
                    adminId, action, targetType, targetId, e);
        }
    }
}
