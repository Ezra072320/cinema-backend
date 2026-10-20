-- 清掉本地联调造的测试数据. 只删验证时建的那几张表, 不碰 movie 等真实数据.
DELETE FROM ticket;
DELETE FROM `order`;
DELETE FROM `schedule`;
DELETE FROM seat_couple_group;
DELETE FROM hall_seat_version;
DELETE FROM seat;
DELETE FROM hall;

SELECT
    (SELECT COUNT(*) FROM hall)              AS hall,
    (SELECT COUNT(*) FROM seat)              AS seat,
    (SELECT COUNT(*) FROM seat_couple_group) AS couple_group,
    (SELECT COUNT(*) FROM `schedule`)        AS schedule,
    (SELECT COUNT(*) FROM ticket)            AS ticket,
    (SELECT COUNT(*) FROM `order`)           AS orders;
