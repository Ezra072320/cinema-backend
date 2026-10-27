package edu.czu.cinema.remote;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class OperationJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class)
    public <T> T run(
            String key, Long uid, String kind, Object request, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 64)
            throw new BusinessException("缺少有效的操作编号");
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(json.writeValueAsBytes(request)));
            jdbc.update(
                    "INSERT INTO"
                        + " service_operation(operation_id,user_id,operation_type,request_hash)"
                        + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE operation_type=operation_type",
                    key,
                    uid,
                    kind,
                    hash);
            var row =
                    jdbc.queryForMap(
                            "SELECT user_id,operation_type,request_hash,result_json FROM"
                                + " service_operation WHERE operation_id=? FOR UPDATE",
                            key);
            if (((Number) row.get("user_id")).longValue() != uid
                    || !kind.equals(row.get("operation_type"))
                    || !hash.equals(row.get("request_hash")))
                throw new BusinessException("操作编号与请求不匹配");
            if (row.get("result_json") != null)
                return json.readValue((String) row.get("result_json"), type);
            T result = action.get();
            jdbc.update(
                    "UPDATE service_operation SET result_json=? WHERE operation_id=?",
                    json.writeValueAsString(result),
                    key);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("操作记录失败", e);
        }
    }

    public String result(String key, Long uid) {
        var rows =
                jdbc.queryForList(
                        "SELECT result_json FROM service_operation WHERE operation_id=? AND"
                            + " user_id=?",
                        String.class,
                        key,
                        uid);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
