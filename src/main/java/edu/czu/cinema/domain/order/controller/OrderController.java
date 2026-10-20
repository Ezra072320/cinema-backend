package edu.czu.cinema.domain.order.controller;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.common.api.R;
import edu.czu.cinema.common.util.SecurityUtils;
import edu.czu.cinema.domain.order.dto.OrderAddDTO;
import edu.czu.cinema.domain.order.dto.OrderPayDTO;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "08. 订单域")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "创建订单（锁定座位）")
    @PostMapping("/user/createOrder")
    public R<Long> create(@Valid @RequestBody OrderAddDTO dto) {
        SecurityUtils.requireUserId();
        return R.ok(orderService.createOrder(dto));
    }

    @Operation(summary = "订单详情（必须本人；越权返 2002）")
    @GetMapping("/order/{id}")
    public R<OrderVO> detail(@PathVariable Long id) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(orderService.detail(id, uid));
    }

    @Operation(summary = "我的订单（按状态；page/limit 可选，不传即全量兼容旧前端）")
    @GetMapping("/user/orders/{status}")
    public R<List<OrderVO>> listByUser(@PathVariable Integer status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer limit) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(orderService.listByUser(uid, status, page, limit));
    }

    @Operation(summary = "当前用户订单按状态计数（个人中心 tab 角标）")
    @GetMapping("/order/countByStatus")
    public R<Map<Integer, Long>> countByStatus() {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(orderService.countByStatus(uid));
    }

    @Operation(summary = "申请退款")
    @PostMapping("/user/refund/{id}")
    public R<String> refund(@PathVariable Long id) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(orderService.refund(id, uid));
    }

    @Operation(summary = "支付订单")
    @PostMapping("/order/pay")
    public R<OrderVO> pay(@Valid @RequestBody OrderPayDTO dto) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(orderService.pay(dto.orderId(), dto.channel(), uid));
    }

    @Operation(summary = "用户主动取消待支付订单（释放座位、状态置已关闭）")
    @PostMapping("/user/cancelOrder/{id}")
    public R<String> cancel(@PathVariable Long id) {
        Long uid = SecurityUtils.requireUserId();
        return R.ok(orderService.cancel(id, uid));
    }

    @Operation(summary = "订单管理（管理员）")
    @GetMapping("/admin/order/list")
    public R<PageResult<OrderVO>> adminList(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int limit,
                                            @RequestParam(required = false, defaultValue = "5") Integer status,
                                            @RequestParam(required = false) String keyword) {
        SecurityUtils.requireAdminId();
        return R.ok(orderService.adminPage(page, limit, status, keyword));
    }
}