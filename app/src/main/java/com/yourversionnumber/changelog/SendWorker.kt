package com.yourversionnumber.changelog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourversionnumber.changelog.kit.perform
import com.yourversionnumber.changelog.kit.prepare

/**
 * Sends the Outbox, oldest first, each item under its own idempotency key.
 * WorkManager runs it once there is a connection, and again with backoff
 * while Release Notes can't be reached. Only the signed-in account's writes
 * go; another account's wait on Log for the owner.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        // The token first, then whose writes it sends: a sign-in sets the account before the token.
        val api = graph.api() ?: return Result.success()
        val recipient = graph.recipient
        val report = graph.outbox.flush(
            prepare = { api.prepare(it, graph.outbox) },
            prepareAttach = { api.prepare(it, graph.outbox) },
            recipient = recipient,
        ) { api.perform(it) }
        graph.record(report)
        val stopped = report.stopped ?: return Result.success()
        return when {
            stopped.signedOut -> {
                graph.tokenLost(stopped.message)
                Result.success()
            }
            stopped.retryAfterSeconds != null -> {
                graph.send(delaySeconds = stopped.retryAfterSeconds!!)
                Result.success()
            }
            stopped.retryable -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val NAME = "outbox"
    }
}
