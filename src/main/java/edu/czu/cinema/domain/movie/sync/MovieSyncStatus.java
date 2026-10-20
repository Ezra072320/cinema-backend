package edu.czu.cinema.domain.movie.sync;

import java.util.List;

/**
 * 一次同步任务的进度快照（不可变，供前端轮询）.
 *
 * <p>同步要跑几分钟，前端靠 2 秒一次的轮询显示进度条，所以这个结构要能随时安全地读。
 *
 * @param running    是否正在同步
 * @param total      待同步影片总数
 * @param done       已处理数（含失败）
 * @param ok         成功数
 * @param fail       失败数
 * @param current    当前正在抓取的片名
 * @param startedAt  本次开始时间（epoch millis），从未跑过为 0
 * @param finishedAt 本次结束时间，未结束为 0
 * @param results    逐部结果，失败项带原因
 */
public record MovieSyncStatus(
        boolean running,
        int total,
        int done,
        int ok,
        int fail,
        String current,
        long startedAt,
        long finishedAt,
        List<Item> results,
        int created,
        int updated,
        int skipped,
        int partial,
        int pending,
        String message,
        String state,
        String mode
) {

    /** 单部影片的同步结果. */
    public record Item(String title, boolean ok, String message, Long movieId, List<String> missingFields) {}

    public static MovieSyncStatus idle() {
        return new MovieSyncStatus(false, 0, 0, 0, 0, null, 0L, 0L, List.of(), 0, 0, 0, 0, 0, "尚未同步", "IDLE", "SYNC");
    }
}
