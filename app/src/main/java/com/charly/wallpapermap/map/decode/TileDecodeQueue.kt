package com.charly.wallpapermap.map.decode

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

object TileDecodeQueue {

    private val queue = PriorityBlockingQueue<TileDecodeJob>(
        100,
        compareBy<TileDecodeJob> { it.priority }
            .thenBy { it.timestamp }
    )

    private val scheduled = ConcurrentHashMap.newKeySet<Long>()

    fun push(job: TileDecodeJob) {
        // Evitamos duplicar laburo si ya está en cola
        if (scheduled.add(job.tileIndex)) {
            queue.put(job)
        }
    }

    fun take(): TileDecodeJob {
        val job = queue.take()
        scheduled.remove(job.tileIndex)
        return job
    }
}