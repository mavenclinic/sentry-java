package io.sentry

import io.sentry.SentryOptions.BeforeEmitMetricCallback
import io.sentry.metrics.IMetricsClient
import io.sentry.metrics.LocalMetricsAggregator
import io.sentry.metrics.MetricType
import io.sentry.metrics.MetricsHelper
import io.sentry.metrics.MetricsHelperTest
import io.sentry.test.DeferredExecutorService
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsAggregatorTest {

    private class Fixture {
        val client = mock<IMetricsClient>()
        val logger = mock<ILogger>()
        val dateProvider = SentryDateProvider {
            SentryLongDate(TimeUnit.MILLISECONDS.toNanos(currentTimeMillis))
        }
        var currentTimeMillis: Long = 0
        var executorService = DeferredExecutorService()

        fun getSut(
            maxWeight: Int = MetricsHelper.MAX_TOTAL_WEIGHT,
            beforeEmitMetricCallback: BeforeEmitMetricCallback? = null
        ): MetricsAggregator {
            return MetricsAggregator(
                client,
                logger,
                dateProvider,
                maxWeight,
                beforeEmitMetricCallback,
                executorService
            )
        }
    }

    private val fixture = Fixture()

    @BeforeTest
    fun setup() {
        MetricsHelper.setFlushShiftMs(0)
    }

    @Test
    fun `flush is a no-op when there's nothing to flush`() {
        val aggregator = fixture.getSut()

        // when no metrics are collected

        // then flush does nothing
        aggregator.flush(false)

        verify(fixture.client, never()).captureMetrics(any())
    }

    @Test
    fun `flush performs a flush when needed`() {
        val aggregator = fixture.getSut()

        // when a metric is emitted
        fixture.currentTimeMillis = 20_000
        aggregator.increment("key", 1.0, null, null, 20_001, null)

        // then flush does nothing because there's no data inside the flush interval
        aggregator.flush(false)
        verify(fixture.client, never()).captureMetrics(any())

        // as times moves on
        fixture.currentTimeMillis = 30_000

        // the metric should be flushed
        aggregator.flush(false)
        verify(fixture.client).captureMetrics(any())
    }

    @Test
    fun `force flush performs a flushing`() {
        val aggregator = fixture.getSut()
        // when a metric is emitted
        fixture.currentTimeMillis = 20_000
        aggregator.increment("key", 1.0, null, null, 20_001, null)

        // then force flush flushes the metric
        aggregator.flush(true)
        verify(fixture.client).captureMetrics(any())
    }

    @Test
    fun `same metrics are aggregated when in same bucket`() {
        val aggregator = fixture.getSut()

        fixture.currentTimeMillis = 20_000

        aggregator.increment(
            "name",
            1.0,
            MeasurementUnit.Custom("apples"),
            mapOf("a" to "b"),
            20_001,
            null
        )
        aggregator.increment(
            "name",
            1.0,
            MeasurementUnit.Custom("apples"),
            mapOf("a" to "b"),
            25_001,
            null
        )

        // then flush does nothing because there's no data inside the flush interval
        aggregator.flush(true)

        verify(fixture.client).captureMetrics(
            check {
                val metrics = MetricsHelperTest.parseMetrics(it.encodeToStatsd())
                assertEquals(1, metrics.size)
                assertEquals(
                    MetricsHelperTest.Companion.StatsDMetric(
                        20,
                        "name",
                        "apples",
                        "c",
                        listOf("2.0"),
                        mapOf("a" to "b")
                    ),
                    metrics[0]
                )
            }
        )
    }

    @Test
    fun `different metrics are not aggregated when in same bucket`() {
        val aggregator = fixture.getSut()

        // when different metrics are emitted in the same bucket
        fixture.currentTimeMillis = 20_000
        aggregator.distribution(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit1"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit1"),
            mapOf("key1" to "value0"),
            20_001,
            null
        )
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit1"),
            mapOf("key1" to "value1"),
            20_001,
            null
        )

        aggregator.flush(true)

        // then all of them are emitted separately
        verify(fixture.client).captureMetrics(
            check {
                val metrics = MetricsHelperTest.parseMetrics(it.encodeToStatsd())
                assertEquals(5, metrics.size)
            }
        )
    }

    @Test
    fun `once the aggregator is closed, emissions are ignored`() {
        val aggregator = fixture.getSut()

        // when aggregator is closed
        aggregator.close()

        // and a metric is emitted
        fixture.currentTimeMillis = 20_000
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )

        // then the metric is never captured
        aggregator.flush(true)
        verify(fixture.client, never()).captureMetrics(any())
    }

    @Test
    fun `all metric types can be emitted`() {
        val aggregator = fixture.getSut()

        fixture.currentTimeMillis = 20_000
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.distribution(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.set(
            "name0-string",
            "Hello",
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.set(
            "name0-int",
            1234,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )
        aggregator.gauge(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )

        aggregator.flush(true)
        verify(fixture.client).captureMetrics(
            check {
                val metrics = MetricsHelperTest.parseMetrics(it.encodeToStatsd())
                assertEquals(5, metrics.size)
            }
        )
    }

    @Test
    fun `flushing gets scheduled and captures metrics`() {
        val aggregator = fixture.getSut()

        // when nothing happened so far
        // then no flushing is scheduled
        assertFalse(fixture.executorService.hasScheduledRunnables())

        // when a metric gets emitted
        fixture.currentTimeMillis = 20_000
        aggregator.increment(
            "name0",
            1.0,
            MeasurementUnit.Custom("unit0"),
            mapOf("key0" to "value0"),
            20_001,
            null
        )

        // then a flush is scheduled
        assertTrue(fixture.executorService.hasScheduledRunnables())

        // flush is executed, but there are other metric to capture and it's scheduled again
        fixture.executorService.runAll()
        verify(fixture.client, never()).captureMetrics(any())
        assertTrue(fixture.executorService.hasScheduledRunnables())

        // after the flush is executed, the metric is captured
        fixture.currentTimeMillis = 31_000
        fixture.executorService.runAll()
        verify(fixture.client).captureMetrics(any())

        // there is no other metric to capture, so flush is not scheduled again
        assertFalse(fixture.executorService.hasScheduledRunnables())
    }

    @Test
    fun `metric emits get forwarded to local aggregator`() {
        val aggregator = fixture.getSut()

        val localAggregator = mock<LocalMetricsAggregator>()

        // when a metric gets emitted
        val type = MetricType.Counter
        val key = "name0"
        val value = 4.0
        val unit = MeasurementUnit.Custom("unit0")
        val tags = mapOf("key0" to "value0")
        val timestamp = 20_001L

        aggregator.increment(
            key,
            value,
            unit,
            tags,
            timestamp,
            localAggregator
        )

        verify(localAggregator).add(
            MetricsHelper.getMetricBucketKey(type, key, unit, tags),
            type,
            key,
            value,
            unit,
            tags
        )
    }

    @Test
    fun `a set metric forwards a value of 1 to the local aggregator`() {
        val aggregator = fixture.getSut()

        val localAggregator = mock<LocalMetricsAggregator>()

        // when a new set metric gets emitted
        val type = MetricType.Set
        val key = "name0"
        val value = 1235
        val unit = MeasurementUnit.Custom("unit0")
        val tags = mapOf("key0" to "value0")
        val timestamp = 20_001L

        aggregator.set(
            key,
            value,
            unit,
            tags,
            timestamp,
            localAggregator
        )

        // then the local aggregator receives a value of 1
        verify(localAggregator).add(
            MetricsHelper.getMetricBucketKey(type, key, unit, tags),
            type,
            key,
            1.0,
            unit,
            tags
        )

        // if the same set metric is emitted again
        aggregator.set(
            key,
            value,
            unit,
            tags,
            timestamp,
            localAggregator
        )

        // then the local aggregator receives a value of 0
        verify(localAggregator).add(
            MetricsHelper.getMetricBucketKey(type, key, unit, tags),
            type,
            key,
            0.0,
            unit,
            tags
        )
    }

    fun `weight is considered for force flushing`() {
        // weight is determined by number of buckets + weight of metrics
        val aggregator = fixture.getSut(5)

        // when 3 values are emitted
        for (i in 0 until 3) {
            aggregator.distribution(
                "name",
                i.toDouble(),
                null,
                null,
                fixture.currentTimeMillis,
                null
            )
        }
        // no metrics are captured by the client
        fixture.executorService.runAll()
        verify(fixture.client, never()).captureMetrics(any())

        // once we have 4 values and one bucket = weight of 5
        aggregator.distribution(
            "name",
            10.0,
            null,
            null,
            fixture.currentTimeMillis,
            null
        )
        // then flush without force still captures all metrics
        fixture.executorService.runAll()
        verify(fixture.client).captureMetrics(any())
    }

    @Test
    fun `flushing is immediately scheduled if add operations causes too much weight`() {
        fixture.executorService = mock()
        val aggregator = fixture.getSut(1)

        verify(fixture.executorService, never()).schedule(any(), any())

        // when 1 value is emitted
        aggregator.distribution(
            "name",
            1.0,
            null,
            null,
            fixture.currentTimeMillis,
            null
        )

        // flush is immediately scheduled
        verify(fixture.executorService).schedule(any(), eq(0))
    }

    @Test
    fun `flushing is deferred scheduled if add operations does not cause too much weight`() {
        fixture.executorService = mock()
        val aggregator = fixture.getSut(10)

        // when 1 value is emitted
        aggregator.distribution(
            "name",
            1.0,
            null,
            null,
            fixture.currentTimeMillis,
            null
        )

        // flush is scheduled for later
        verify(fixture.executorService).schedule(any(), eq(MetricsHelper.FLUSHER_SLEEP_TIME_MS))
    }

    @Test
    fun `key and tags are passed down to beforeEmitMetricCallback`() {
        var lastKey: String? = null
        var lastTags: Map<String, String>? = null
        val aggregator = fixture.getSut(beforeEmitMetricCallback = { key, tags ->
            lastKey = key
            lastTags = tags
            return@getSut false
        })

        val key = "metric-key"
        val tags = mapOf(
            "tag-key" to "tag-value"
        )
        aggregator.increment(key, 1.0, null, tags, 20_001, null)
        assertEquals(key, lastKey)
        assertEquals(tags, lastTags)
    }

    @Test
    fun `if before emit callback returns true, metric is emitted`() {
        val aggregator = fixture.getSut(beforeEmitMetricCallback = { key, tags -> true })
        aggregator.increment("key", 1.0, null, null, 20_001, null)
        aggregator.flush(true)
        verify(fixture.client).captureMetrics(any())
    }

    @Test
    fun `if before emit callback returns false, metric is not emitted`() {
        val aggregator = fixture.getSut(beforeEmitMetricCallback = { key, tags -> false })
        aggregator.increment("key", 1.0, null, null, 20_001, null)
        aggregator.flush(true)
        verify(fixture.client, never()).captureMetrics(any())
    }

    @Test
    fun `if before emit throws, metric is emitted`() {
        val aggregator = fixture.getSut(beforeEmitMetricCallback = { key, tags -> throw RuntimeException() })
        aggregator.increment("key", 1.0, null, null, 20_001, null)
        aggregator.flush(true)
        verify(fixture.client).captureMetrics(any())
    }
}
