package audit

import com.auras.orbit.providers.Cs3ProviderMetadata
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import java.util.AbstractSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/** Fault injections against the real generation-scoped metadata cache and worker. */
object ProviderMetadataFailureRegression {
    private var checks = 0L
    private fun checkThat(value: Boolean, label: String) {
        checks++
        if (!value) throw AssertionError(label)
    }

    @JvmStatic fun main(args: Array<String>) {
        testPostReadCallbackFailureDoesNotStrandWorker()
        testUnboundedSupportedTypesIterator()
        testSupersededGenerationAndPublishingFailure()
        println("ProviderMetadataFailureRegression: $checks checks passed")
    }

    private fun testPostReadCallbackFailureDoesNotStrandWorker() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = object : MainAPI() {
            override val name: String
                get() {
                    entered.countDown()
                    if (!release.await(3, TimeUnit.SECONDS)) throw AssertionError("test did not release getter")
                    return "first"
                }
        }
        val cache = Cs3ProviderMetadata()
        val key1 = Cs3ProviderMetadata.Key("profile", "plugin", 1, 1, 0, api)
        val key2 = Cs3ProviderMetadata.Key("profile", "plugin", 2, 1, 0, api)
        val calls = AtomicInteger()
        cache.request(key1, "one", BooleanSupplier { true }, Runnable {}, BooleanSupplier {
            if (calls.incrementAndGet() > 2) throw IllegalStateException("late lifecycle callback")
            true
        }, Runnable {})
        checkThat(entered.await(2, TimeUnit.SECONDS), "first generation getter entered")
        cache.request(key2, "two", BooleanSupplier { true }, Runnable {}, BooleanSupplier { true }, Runnable {})
        release.countDown()
        val next = cache.await(key2, 1700)
        checkThat(next.status == Cs3ProviderMetadata.AwaitStatus.READY,
            "late lifecycle exception must not strand queued metadata generation: got ${next.status}")
        checkThat(next.snapshot?.name == "first", "queued generation produces its snapshot")
    }

    private fun testUnboundedSupportedTypesIterator() {
        val examined = AtomicInteger()
        val pathological = object : AbstractSet<TvType>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun iterator(): MutableIterator<TvType> = object : MutableIterator<TvType> {
                override fun remove(): Unit = throw UnsupportedOperationException()
                override fun hasNext() = true
                override fun next(): TvType {
                    if (examined.incrementAndGet() > 100_000) throw IllegalStateException("unbounded metadata iteration")
                    return TvType.Movie
                }
            }
        }
        val api = object : MainAPI() { override val supportedTypes: Set<TvType> = pathological }
        val cache = Cs3ProviderMetadata()
        val key = Cs3ProviderMetadata.Key("p", "infinite", 1, 1, 0, api)
        cache.request(key, "fallback", BooleanSupplier { true }, Runnable {}, BooleanSupplier { true }, Runnable {})
        val result = cache.await(key, 2500)
        checkThat(result.status == Cs3ProviderMetadata.AwaitStatus.READY, "supported types snapshot returned")
        checkThat(examined.get() <= 128, "supported types scan bounded, was ${examined.get()}")
        checkThat(result.snapshot!!.supportedTypes == setOf(TvType.Movie), "first supported type preserved")
    }

    private fun testSupersededGenerationAndPublishingFailure() {
        val api = MainAPI()
        val cache = Cs3ProviderMetadata()
        repeat(256) { index ->
            val key = Cs3ProviderMetadata.Key("p", "plugin", index.toLong(), 2, 0, api)
            cache.request(key, "p", BooleanSupplier { true }, Runnable {}, BooleanSupplier { true }, Runnable {
                throw IllegalStateException("notification handler failed")
            })
            val result = cache.await(key, 2000)
            checkThat(result.status == Cs3ProviderMetadata.AwaitStatus.READY, "next generation $index completed")
            checkThat(result.snapshot?.name == "Provider", "next generation $index returned stable name")
        }
    }
}
