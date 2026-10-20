package edu.czu.cinema.ai.controller;

import edu.czu.cinema.ai.dto.AiChatDTO;
import edu.czu.cinema.ai.entity.AiSession;
import edu.czu.cinema.ai.service.AiChatService;
import edu.czu.cinema.ai.service.AiActionService;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.common.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@Tag(name = "11. AI 客服")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService chatService;
    private final AiActionService actions;

    // SSE建立前的失败必须明确返回JSON；否则全局R对象可能被当作text/event-stream序列化。
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> businessError(BusinessException e) {
        HttpStatus status = e.getCode() == ResultCode.FORBIDDEN.getCode() ? HttpStatus.FORBIDDEN
                : e.getCode() == ResultCode.UNAUTHORIZED.getCode() ? HttpStatus.UNAUTHORIZED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> invalidRequest(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(R.fail("问题或会话参数不合法，请检查输入长度"));
    }

    @Operation(summary = "本会话服务单（只返回本人数据）")
    @GetMapping("/actions/{chatId}")
    public R<List<AiActionService.View>> actions(@PathVariable String chatId) {
        return R.ok(actions.list(SecurityUtils.requireUserId(), chatId));
    }

    @Operation(summary = "用户确认办理服务单（幂等执行，不注册为模型工具）")
    @PostMapping("/action/{id}/confirm")
    public R<AiActionService.View> confirm(@PathVariable String id) {
        return R.ok(actions.confirm(SecurityUtils.requireUserId(), id));
    }

    @Operation(summary = "撤销待确认服务单")
    @PostMapping("/action/{id}/dismiss")
    public R<AiActionService.View> dismiss(@PathVariable String id) {
        return R.ok(actions.dismiss(SecurityUtils.requireUserId(), id));
    }

    /**
     * 流式对话(SSE).
     *
     * <p>2026-10-19 由 GET query 改 POST body: 超长提问会顶爆 URL 上限, 且提问进访问日志。
     * 帧契约: 每条事件 {@code data: "<JSON 字符串字面量>"} —— 前端 JSON.parse 得到一个 token,
     * 换行/引号已被转义, 不会撕开 SSE 帧; 流结束=连接关闭(无 [DONE] 哨兵).
     */
    @Operation(summary = "流式对话（SSE）")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody AiChatDTO dto) {
        Long uid = SecurityUtils.requireUserId();
        return chatService.streamChat(uid, dto.chatId(), dto.question());
    }

    @Operation(summary = "用户全部会话")
    @GetMapping("/sessions")
    public R<List<AiSession>> sessions() {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(chatService.sessionsOf(uid));
    }

    @Operation(summary = "单次会话历史消息")
    @GetMapping("/history/{chatId}")
    public R<List<AiSession.Message>> history(@PathVariable String chatId) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(chatService.history(uid, chatId));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/session/{chatId}")
    public R<Boolean> deleteSession(@PathVariable String chatId) {
        Long uid = SecurityUtils.requireUserId();
        chatService.deleteSession(uid, chatId);
        return R.ok(true);
    }
}
