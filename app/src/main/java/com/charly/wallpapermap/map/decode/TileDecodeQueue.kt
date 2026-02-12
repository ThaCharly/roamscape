package com.charly.wallpapermap.map.decode

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.HashSet
import kotlinx.coroutines.launch

object TileDecodeQueue {

    private const val MAX_QUEUE_SIZE = 300

    private val mutex = Mutex()

    private val queue = PriorityQueue<TileDecodeJob>(
        compareBy<TileDecodeJob> { it.priority }
    )

    // Evita duplicados
    private val enqueued = HashSet<Long>()

    suspend fun take(): TileDecodeJob {
        while (true) {
            mutex.withLock {
                val job = queue.poll()
                if (job != null) {
                    enqueued.remove(job.tileIndex)
                    return job
                }
            }
            kotlinx.coroutines.delay(5)
        }
    }

    fun push(job: TileDecodeJob) {
        kotlinx.coroutines.GlobalScope.launch {
            mutex.withLock {

                // Evitar duplicados
                if (enqueued.contains(job.tileIndex)) return@withLock

                // Backpressure policy
                if (queue.size >= MAX_QUEUE_SIZE &&
                    job.priority == TileDecodeScheduler.PRIORITY_PREFETCH
                ) {
                    return@withLock
                }

                queue.add(job)
                enqueued.add(job.tileIndex)
            }
        }
    }

    fun contains(tileIndex: Long): Boolean {
        return enqueued.contains(tileIndex)
    }
}
