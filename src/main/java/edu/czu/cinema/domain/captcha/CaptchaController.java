package edu.czu.cinema.domain.captcha;

import edu.czu.cinema.common.api.R;
import edu.czu.cinema.domain.captcha.dto.CaptchaCheckDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图形验证码接口.
 *
 * <p>图片接口直接返回 {@code image/png},前端可作为 {@code <img src>} 直接引用;
 * ticket 由前端生成 UUID 后通过 {@code ?ticket=} 传入,服务端只负责按 ticket 存 code.</p>
 *
 * <p>与 login 端点的关系:票据是一次性令牌（GETDEL 原子消费），无论对错用一次就销毁。
 * 因此前端应跳过 {@code /check}，把 ticket+code 随登录请求一起提交，
 * 由 login 端点原子地 {@link CaptchaService#verifyAndConsume}，避免 check 后票据失效。</p>
 */
@Tag(name = "13. 图形验证码")
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {

    private final CaptchaService captchaService;

    @Operation(summary = "生成图片验证码(直接返回 PNG,可作 <img src> 直接引用)")
    @GetMapping(value = "/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@RequestParam String ticket, HttpServletRequest request) {
        if (ticket == null || ticket.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        byte[] png = captchaService.generate(ticket, LoginRiskService.resolveClientIp(request));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                .body(png);
    }

    @Operation(summary = "校验图形验证码(一次性：无论对错票据都销毁)")
    @PostMapping("/check")
    public R<Boolean> check(@Valid @RequestBody CaptchaCheckDTO dto) {
        // 注意：check 成功即消费票据，随后再把同一 ticket 随登录提交会失败。
        // 前端应把 ticket+code 随登录请求一起提交,由登录端点原子消费，无需先 check。
        boolean ok = captchaService.verifyAndConsume(dto.ticket(), dto.code());
        return R.ok(ok);
    }
}
