package edu.czu.cinema;

import edu.czu.cinema.ai.dto.AiActionCommand;
import edu.czu.cinema.ai.service.*;
import edu.czu.cinema.common.exception.BusinessException;
import edu.czu.cinema.domain.hall.service.HallService;
import edu.czu.cinema.domain.hall.service.SeatLabelService;
import edu.czu.cinema.domain.movie.service.MovieService;
import edu.czu.cinema.domain.order.service.OrderRefundPolicy;
import edu.czu.cinema.domain.order.service.OrderService;
import edu.czu.cinema.domain.schedule.service.ScheduleService;
import edu.czu.cinema.domain.ticket.entity.Ticket;
import edu.czu.cinema.domain.ticket.service.TicketService;
import edu.czu.cinema.domain.score.service.ScoreService;
import edu.czu.cinema.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 全部工具均为查询或拟单；执行写操作的确认接口绝不暴露给模型。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTool {
    private final AiCatalogService catalog;
    private final MovieService movies;
    private final ScheduleService schedules;
    private final HallService halls;
    private final TicketService tickets;
    private final SeatLabelService labels;
    private final OrderService orders;
    private final UserService users;
    private final ScoreService scores;
    private final AiActionService actions;

    @Tool(description = "搜索/推荐真实影片。keyword支持片名、别名、类型、导演或演员（一次一个关键词）；showing=true仅有未开场本店排片，false无可购场次，空为全部。按评分排序，page从1起，每页12条，hasMore表示可翻页。预算和日期需再查询排片。")
    public ToolResult searchMovies(@ToolParam(required = false) String keyword,
                               @ToolParam(required = false) Boolean showing,
                               @ToolParam(required = false) Integer page) {
        return safe(() -> catalog.movies(keyword, showing, page));
    }

    @Tool(description = "影片详情：简介、时长、类型、导演、编剧、演员角色、预告片和剧照数量、资料来源、本人想看状态及真实站内入口。movieId必须来自搜索结果。")
    public ToolResult movieDetails(Long movieId, ToolContext context) {
        return safe(() -> {
            var identity = AiIdentity.from(context);
            var info = movies.info(movieId);
            var m = info.getBase();
            return data("movieId", movieId, "title", m.getTitle(), "summary", m.getSummary(),
                    "runtimeMinutes", m.getRuntimeMin(), "genres", m.getCat(), "language", m.getLanguage(),
                    "area", m.getArea(), "score", m.getScore(), "scoreSource", m.getScoreSource(),
                    "releaseDate", m.getReleaseDate(), "source", m.getSource(),
                    "crew", info.getCrew() == null ? List.of() : info.getCrew().stream().limit(40)
                            .map(c -> data("name", c.getPerson().getName(), "roleType", c.getRoleType(),
                                    "character", c.getCharacterName(), "job", c.getJobName())).toList(),
                    "imageCount", info.getImages() == null ? 0 : info.getImages().size(),
                    "trailerCount", info.getVideos() == null ? 0 : info.getVideos().size(),
                    "hasTrailer", m.getTrailerUrl() != null || (info.getVideos() != null && !info.getVideos().isEmpty()),
                    "wanted", movies.isWant(movieId, identity.userId()), "detailPath", "/movie/" + movieId,
                    "schedulePath", "/hall/" + movieId);
        });
    }

    @Tool(description = "查询排行榜。kind=SALES为本店真实销售额榜，BOX_OFFICE为全国公开累计票房，口径不同不能混用。")
    public ToolResult movieRanking(String kind) {
        return safe(() -> {
            if ("SALES".equalsIgnoreCase(kind)) return data("kind", "本店真实销售额", "rows", movies.topSales(), "path", "/main");
            if ("BOX_OFFICE".equalsIgnoreCase(kind)) return data("kind", "全国公开累计票房", "rows", movies.topBoxOffice(), "path", "/main");
            throw new BusinessException("排行榜类型请用SALES或BOX_OFFICE");
        });
    }

    @Tool(description = "查某天尚未开场、未取消的真实场次及余票。date为yyyy-MM-dd或今天/明天/后天；空为今天。可按movieId/hallId、HH:mm时间段after/before、单张最高预算maxPrice筛选；page从1翻页。最低价是当前可售票价格，情侣座须整对购买。")
    public ToolResult listSchedules(@ToolParam(required = false) String date,
                                @ToolParam(required = false) Long movieId,
                                @ToolParam(required = false) Long hallId,
                                @ToolParam(required = false) String after,
                                @ToolParam(required = false) String before,
                                @ToolParam(required = false) BigDecimal maxPrice,
                                @ToolParam(required = false) Integer page) {
        return safe(() -> catalog.schedules(date, movieId, hallId, after, before, maxPrice, page));
    }

    @Tool(description = "列出本店实际影厅、类型与容量。1标准2D/2标准3D/3 IMAX2D/4 IMAX3D；具体座型和可售库存须查询某场次座位。")
    public ToolResult listHalls() {
        return safe(() -> halls.listAll().stream().map(h -> data("hallId", h.getId(), "name", h.getName(),
                "type", h.getType(), "capacity", h.getCapacity(), "path", "/hall")).toList());
    }

    @Tool(description = "查询某场次真实可售座位、显示座号label、下单用ticketId及每张票实际价格。seatType可选1标准/2情侣/3 VIP/4无障碍；page从1起每页40张。情侣座的pairedTicketIds必须一起选择。余座不会预留。")
    public ToolResult availableSeats(Long scheduleId, @ToolParam(required = false) Integer seatType,
                                 @ToolParam(required = false) Integer page) {
        return safe(() -> {
            int p = page == null ? 1 : page;
            if (p < 1 || p > 10000) throw new BusinessException("页码须在1至10000之间");
            if (seatType != null && !List.of(1, 2, 3, 4).contains(seatType)) throw new BusinessException("未知座位类型");
            var s = schedules.seatsOfSchedule(scheduleId);
            if (s == null) throw new BusinessException("场次不存在");
            boolean bookable = Integer.valueOf(1).equals(s.getStatus()) && s.getStartTime().isAfter(LocalDateTime.now());
            var priceById = tickets.listBySchedule(scheduleId).stream().collect(Collectors.toMap(Ticket::getId, t -> t));
            var seatLabels = labels.labelsOf(s.getSeatVersionId());
            Map<Long, Long> ticketBySeat = new HashMap<>();
            s.getSeats().stream().filter(x -> x.getTicketId() != null).forEach(x -> ticketBySeat.put(x.getSeatId(), x.getTicketId()));
            Map<Long, List<Long>> pairTickets = new HashMap<>();
            for (var pair : s.getCoupleGroups()) {
                Long a = ticketBySeat.get(pair.getSeatId1()), b = ticketBySeat.get(pair.getSeatId2());
                if (a != null && b != null) { pairTickets.put(a, List.of(a, b)); pairTickets.put(b, List.of(a, b)); }
            }
            var available = s.getSeats().stream().filter(x -> bookable && Integer.valueOf(1).equals(x.getStatus())
                    && Integer.valueOf(1).equals(x.getTicketStatus()) && priceById.containsKey(x.getTicketId())
                    && (seatType == null || Objects.equals(seatType, x.getType())))
                    .sorted(Comparator.comparing((edu.czu.cinema.domain.schedule.vo.ScheduleSeatsVO.SeatItem x) -> x.getY())
                            .thenComparing(x -> x.getX())).toList();
            var rows = available.stream().skip((long)(p - 1) * 40).limit(40).map(x -> data(
                    "ticketId", x.getTicketId(), "label", seatLabels.get(x.getSeatId()), "type", x.getType(),
                    "price", priceById.get(x.getTicketId()).getPrice(), "pairedTicketIds", pairTickets.get(x.getTicketId()))).toList();
            return data("scheduleId", scheduleId, "movie", s.getMovieName(), "hall", s.getHallName(),
                    "startTime", s.getStartTime(), "bookable", bookable, "totalAvailableTickets", available.size(),
                    "page", p, "hasMore", (long)p * 40 < available.size(), "rows", rows, "path", "/seats/" + scheduleId);
        });
    }

    @Tool(description = "查询本人的订单，按订单号片段或片名keyword筛选。status=1待支付/2已支付/3已退款/4已关闭/5全部/6已完成；空为全部，page从1起。模糊匹配多单时请用户明确要操作哪单。")
    public ToolResult myOrders(@ToolParam(required = false) Integer status, @ToolParam(required = false) String keyword,
                           @ToolParam(required = false) Integer page, ToolContext context) {
        return safe(() -> {
            Long uid = AiIdentity.from(context).userId();
            var result = catalog.orders(uid, status, keyword, page);
            @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) result.get("rows");
            result.put("rows", rows.stream().map(row -> orderData(((Number)row.get("orderId")).longValue(), uid)).toList());
            result.put("path", "/me/orders/5");
            return result;
        });
    }

    @Tool(description = "查询本人某订单详情、实时退款资格与金额预估、已提交评价，以及详情/支付/评价入口。orderId为工具查到的内部ID，不能把订单号当ID。")
    public ToolResult orderDetails(Long orderId, ToolContext context) {
        return safe(() -> orderData(orderId, AiIdentity.from(context).userId()));
    }

    private Object orderData(Long id, Long uid) {
        var o = orders.detail(id, uid); // 必须经已有业务服务做订单归属与超时校验
        // reviewPath 按评价资格（ReviewPolicy 同口径：2/6+场次有效+散场后）下发，而非只看 status==2；
        // 已有评价时也放行到评价页以便编辑原评价。
        String reviewPath = (o.isCanReview() || o.isReviewed()) ? "/score/" + o.getMovieId() + "/" + id : null;
        return data("order", o, "refundPreview", OrderRefundPolicy.quote(o.getStatus(), o.getTotalAmount(),
                        o.getStartTime(), LocalDateTime.now()), "review", scores.getByOrder(id),
                "detailPath", "/order/" + id, "payPath", Integer.valueOf(1).equals(o.getStatus()) ? "/pay/" + id : null,
                "reviewPath", reviewPath,
                "note", "退款预估非到账记录；支付和退款均为项目模拟业务。评价不改变订单状态，可再次编辑。");
    }

    @Tool(description = "查询本人安全的账户资料：昵称、年龄、状态、注册时间及资料/改密入口。不会返回密码、openid、令牌。")
    public ToolResult myProfile(ToolContext context) {
        return safe(() -> {
            var u = users.getById(AiIdentity.from(context).userId());
            if (u == null) throw new BusinessException("用户不存在");
            return data("name", u.getName(), "age", u.getAge(), "status", u.getStatus(), "joinedAt", u.getCreateTime(),
                    "profilePath", "/me/info", "passwordPath", "/auth/reset");
        });
    }

    @Tool(description = "查询本人想看清单，每页12部，page从1起；想看不保证本店有场次，请继续查排片。")
    public ToolResult myWantList(@ToolParam(required = false) Integer page, ToolContext context) {
        return safe(() -> {
            var ids = movies.userWantList(AiIdentity.from(context).userId());
            int p = page == null ? 1 : page;
            if (p < 1 || p > 10000) throw new BusinessException("页码须在1至10000之间");
            return data("total", ids.size(), "page", p, "hasMore", (long)p * 12 < ids.size(),
                    "rows", ids.stream().skip((long)(p - 1) * 12).limit(12).map(id -> {
                        try { var m = movies.info(id).getBase(); return data("movieId", id, "title", m.getTitle(),
                                "path", "/movie/" + id, "schedulePath", "/hall/" + id); }
                        catch (BusinessException e) { return data("movieId", id, "unavailable", true); }
                    }).toList(), "path", "/me/wants");
        });
    }

    @Tool(description = "用户已明确指定场次和座位后，必须调用本工具生成购票待确认服务单。scheduleId和ticketIds来自刚查到的可售座位；情侣须整对且最多6张。不会锁座或支付。成功后页面自动展示真实服务单，不能编造确认链接。")
    public ToolResult prepareBooking(Long scheduleId, List<Long> ticketIds, ToolContext context) {
        return prepare(AiActionCommand.builder().type(AiActionCommand.Type.CREATE_ORDER)
                .scheduleId(scheduleId).ticketIds(ticketIds).build(), context);
    }

    @Tool(description = "为本人明确指定的待支付订单生成取消确认单。只有工具返回成功才能说已拟单，待用户点击确认后才关闭并释放座位。")
    public ToolResult prepareCancellation(Long orderId, ToolContext context) {
        return prepare(AiActionCommand.builder().type(AiActionCommand.Type.CANCEL_ORDER).orderId(orderId).build(), context);
    }

    @Tool(description = "为本人明确指定的已支付订单生成退票确认单，服务端计算退款资格与金额。待用户点击确认后才模拟退款，不承诺真实到账。")
    public ToolResult prepareRefund(Long orderId, ToolContext context) {
        return prepare(AiActionCommand.builder().type(AiActionCommand.Type.REFUND_ORDER).orderId(orderId).build(), context);
    }

    @Tool(description = "为用户要求的加入/移除想看生成确认单。wanted=true加入，false移除；保持目标状态而不是反复翻转。点击确认前不会修改清单。")
    public ToolResult prepareWant(Long movieId, Boolean wanted, ToolContext context) {
        return prepare(AiActionCommand.builder().type(AiActionCommand.Type.SET_WANT).movieId(movieId).wanted(wanted).build(), context);
    }

    @Tool(description = "为本人已看过、散场后可评价的订单生成评价确认单，score=1至5，content为用户提供的评论原文，可空；禁止代写观点。点击确认才提交；评价不改变订单状态，可再次编辑。")
    public ToolResult prepareReview(Long orderId, Integer score, @ToolParam(required = false) String content, ToolContext context) {
        return prepare(AiActionCommand.builder().type(AiActionCommand.Type.SUBMIT_SCORE).orderId(orderId)
                .score(score).content(content).build(), context);
    }

    @Tool(description = "为用户明确要求修改的昵称和/或年龄生成确认单。昵称2至16非空字符，年龄1至120。未要求更改的字段留空。头像和密码去已有页面操作。")
    public ToolResult prepareProfile(@ToolParam(required = false) String name, @ToolParam(required = false) Integer age, ToolContext context) {
        return prepare(AiActionCommand.builder().type(AiActionCommand.Type.UPDATE_PROFILE).name(name).age(age).build(), context);
    }

    private ToolResult prepare(AiActionCommand command, ToolContext context) {
        return safe(() -> data("confirmationRequired", true, "action", actions.prepare(AiIdentity.from(context), command),
                "instruction", "请用户点击页面下方真实服务单的确认办理按钮；不要创建确认链接，尚未执行业务"));
    }

    @Tool(description = "查询本会话服务单的最新执行状态与结果。PENDING待用户点击确认，DONE已完成，DISMISSED已撤销，EXPIRED已过期。用户询问是否办理成功时应查询此工具，不能根据旧对话猜测。")
    public ToolResult myServiceRequests(ToolContext context) {
        return safe(() -> { var i = AiIdentity.from(context); return actions.list(i.userId(), i.chatId()); });
    }

    public record ToolResult(boolean success, Object data, String message) {}

    private ToolResult safe(Supplier<Object> query) {
        try { return new ToolResult(true, query.get(), null); }
        catch (BusinessException e) { return new ToolResult(false, null, e.getMessage()); }
        catch (Exception e) {
            // 记完整堆栈（不再只记类型名），便于排障又不对模型外泄错误细节；错误文案保持封装语义。
            log.warn("AI业务工具执行失败: {}", e.getMessage(), e);
            return new ToolResult(false, null, "业务服务暂不可用，请稍后重试或使用对应业务页面，不能推测数据或宣称已办理");
        }
    }

    private static Map<String, Object> data(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]);
        return result;
    }
}

