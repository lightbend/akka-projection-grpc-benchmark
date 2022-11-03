package shopping.analytics

import org.HdrHistogram.Histogram
import org.slf4j.LoggerFactory

trait Measure {
  private val log =
    LoggerFactory.getLogger("shopping.analytics.ShoppingCartEventConsumer")

  // JVM System property
  private val lagThresholdMillis =
    Integer.getInteger("ShoppingCartEventConsumer.lag-threshold-ms", 2000)

  private var reportingStartTime = System.nanoTime()

  private var totalCount = 0
  private var throughputCount = 0
  private var lagCount = 0L
  private var reportingCount = 0

  private val percentiles = List(50.0, 75.0, 90.0, 95.0, 99.0, 99.9)
  private val maxHistogramValue = 60L * 1000L
  private var histogram: Histogram = new Histogram(maxHistogramValue, 2)

  def logId: String

  def processedEvent(eventTimestamp: Long): Unit = {
    totalCount += 1
    val lagMillis = System.currentTimeMillis() - eventTimestamp

    histogram.recordValue(math.max(0L, math.min(lagMillis, maxHistogramValue)))

    throughputCount += 1
    val durationMs: Long =
      (System.nanoTime - reportingStartTime) / 1000 / 1000
    // more frequent reporting in the beginning
    val reportAfter =
      if (reportingCount <= 5) 30000 else 180000
    if (durationMs >= reportAfter) {
      reportingCount += 1

      log.info(
        s"$logId #$reportingCount: Processed ${histogram.getTotalCount} events in $durationMs ms, " +
        s"throughput [${1000L * throughputCount / durationMs}] events/s, " +
        s"max lag [${histogram.getMaxValue}] ms, " +
        s"lag percentiles [${percentiles
          .map(p => s"$p%=${histogram.getValueAtPercentile(p)}ms")
          .mkString("; ")}]")
      println(s"$logId #$reportingCount: HDR histogram [${percentiles
        .map(p => s"$p%=${histogram.getValueAtPercentile(p)}ms")
        .mkString("; ")}]")
      histogram.outputPercentileDistribution(System.out, 1.0)

      throughputCount = 0
      histogram = new Histogram(maxHistogramValue, 2)
      reportingStartTime = System.nanoTime
    }

    if (lagMillis > lagThresholdMillis) {
      lagCount += 1
      if ((lagCount == 1) || (lagCount % 1000 == 0))
        log.info(
          "Projection [{}] lag [{}] ms. Total [{}] events.",
          logId,
          lagMillis,
          totalCount)
    } else {
      lagCount = 0
    }

  }
}
