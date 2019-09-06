/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.kafka.benchmarks

import akka.kafka.benchmarks.PerfFixtureHelpers.FilledTopic
import akka.kafka.benchmarks.Timed.runPerfTest
import akka.kafka.benchmarks.app.RunTestCommand
import akka.kafka.testkit.scaladsl.ScalatestKafkaSpec
import org.scalatest.FlatSpecLike

import scala.concurrent.duration._

abstract class BenchmarksBase() extends ScalatestKafkaSpec(0) with FlatSpecLike {

  // Message count multiplier to adapt for shorter local testing
  val factor = 1000

  override def bootstrapServers: String =
    (1 to BuildInfo.kafkaScale).map(i => sys.props(s"kafka_${i}_9094")).mkString(",")

  override def setUp(): Unit = {
    super.setUp()
    waitUntilCluster() {
      _.nodes().get().size == BuildInfo.kafkaScale
    }
  }
}

class ApacheKafkaConsumerNokafka extends BenchmarksBase() {
  it should "bench" in {
    val cmd = RunTestCommand("apache-kafka-plain-consumer-nokafka", bootstrapServers, FilledTopic(2000 * factor, 100))
    runPerfTest(cmd, KafkaConsumerFixtures.noopFixtureGen(cmd), KafkaConsumerBenchmarks.consumePlainNoKafka)
  }
}

class AlpakkaKafkaConsumerNokafka extends BenchmarksBase() {
  it should "bench" in {
    val cmd = RunTestCommand("alpakka-kafka-plain-consumer-nokafka", bootstrapServers, FilledTopic(2000 * factor, 100))
    runPerfTest(cmd,
                ReactiveKafkaConsumerFixtures.noopFixtureGen(cmd),
                ReactiveKafkaConsumerBenchmarks.consumePlainNoKafka)
  }
}

class ApacheKafkaPlainConsumer extends BenchmarksBase() {
  it should "bench" in {
    val cmd = RunTestCommand("apache-kafka-plain-consumer", bootstrapServers, FilledTopic(2000 * factor, 100))
    runPerfTest(cmd, KafkaConsumerFixtures.filledTopics(cmd), KafkaConsumerBenchmarks.consumePlain)
  }
}

class AlpakkaKafkaPlainConsumer extends BenchmarksBase() {
  it should "bench" in {
    val cmd = RunTestCommand("alpakka-kafka-plain-consumer", bootstrapServers, FilledTopic(2000 * factor, 100))
    runPerfTest(cmd, ReactiveKafkaConsumerFixtures.plainSources(cmd), ReactiveKafkaConsumerBenchmarks.consumePlain)
  }
}

class ApacheKafkaBatchedConsumer extends BenchmarksBase() {
  it should "bench with small messages" in {
    val cmd = RunTestCommand("apache-kafka-batched-consumer", bootstrapServers, FilledTopic(1000 * factor, 100))
    runPerfTest(cmd,
                KafkaConsumerFixtures.filledTopics(cmd),
                KafkaConsumerBenchmarks.consumerAtLeastOnceBatched(batchSize = 1000))
  }

  it should "bench with normal messages" in {
    val cmd =
      RunTestCommand("apache-kafka-batched-consumer-normal-msg", bootstrapServers, FilledTopic(1000 * factor, 5 * 1000))
    runPerfTest(cmd,
                KafkaConsumerFixtures.filledTopics(cmd),
                KafkaConsumerBenchmarks.consumerAtLeastOnceBatched(batchSize = 1000))
  }

  it should "bench with normal messages and eight partitions" in {
    val cmd = RunTestCommand("apache-kafka-batched-consumer-normal-msg-8-partitions",
                             bootstrapServers,
                             FilledTopic(msgCount = 1000 * factor, msgSize = 5 * 1000, numberOfPartitions = 8))
    runPerfTest(cmd,
                KafkaConsumerFixtures.filledTopics(cmd),
                KafkaConsumerBenchmarks.consumerAtLeastOnceBatched(batchSize = 1000))
  }
}

class AlpakkaKafkaBatchedConsumer extends BenchmarksBase() {

  it should "bench with small messages" in {
    val cmd = RunTestCommand("alpakka-kafka-batched-consumer", bootstrapServers, FilledTopic(1000 * factor, 100))
    runPerfTest(cmd,
                ReactiveKafkaConsumerFixtures.committableSources(cmd),
                ReactiveKafkaConsumerBenchmarks.consumerAtLeastOnceBatched(batchSize = 1000))
  }

  it should "bench with normal messages" in {
    val cmd = RunTestCommand("alpakka-kafka-batched-consumer-normal-msg", bootstrapServers, FilledTopic(1000 * factor, 5 * 1000))
    runPerfTest(cmd,
                ReactiveKafkaConsumerFixtures.committableSources(cmd),
                ReactiveKafkaConsumerBenchmarks.consumerAtLeastOnceBatched(batchSize = 1000))
  }

  it should "bench with normal messages and eight partitions" in {
    val cmd = RunTestCommand("alpakka-kafka-batched-consumer-normal-msg-8-partitions",
                             bootstrapServers,
      FilledTopic(msgCount = 1000 * factor,
                             msgSize = 5 * 1000,
                             numberOfPartitions = 8))
    runPerfTest(cmd,
                ReactiveKafkaConsumerFixtures.committableSources(cmd),
                ReactiveKafkaConsumerBenchmarks.consumerAtLeastOnceBatched(batchSize = 1000))
  }
}

class ApacheKafkaAtMostOnceConsumer extends BenchmarksBase() {
  it should "bench" in {
    val cmd = RunTestCommand("apache-kafka-at-most-once-consumer", bootstrapServers, FilledTopic(50 * factor, 100))
    runPerfTest(cmd, KafkaConsumerFixtures.filledTopics(cmd), KafkaConsumerBenchmarks.consumeCommitAtMostOnce)
  }
}

class AlpakkaKafkaAtMostOnceConsumer extends BenchmarksBase() {
  it should "bench" in {
    val cmd = RunTestCommand("alpakka-kafka-at-most-once-consumer", bootstrapServers, FilledTopic(50 * factor, 100))
    runPerfTest(cmd,
                ReactiveKafkaConsumerFixtures.committableSources(cmd),
                ReactiveKafkaConsumerBenchmarks.consumeCommitAtMostOnce)
  }
}

class ApacheKafkaPlainProducer extends BenchmarksBase() {
  private val prefix = "apache-kafka-plain-producer"

  it should "bench with small messages" in {
    val cmd = RunTestCommand(prefix, bootstrapServers, FilledTopic(2000 * factor, 100))
    runPerfTest(cmd, KafkaProducerFixtures.initializedProducer(cmd), KafkaProducerBenchmarks.plainFlow)
  }

  it should "bench with normal messages" in {
    val cmd = RunTestCommand(prefix + "-normal-msg", bootstrapServers, FilledTopic(2000 * factor, 5000))
    runPerfTest(cmd, KafkaProducerFixtures.initializedProducer(cmd), KafkaProducerBenchmarks.plainFlow)
  }

  it should "bench with normal messages written to 8 partitions" in {
    val cmd =
      RunTestCommand(prefix + "-normal-msg-8-partitions", bootstrapServers, FilledTopic(2000 * factor, 5000, numberOfPartitions = 8))
    runPerfTest(cmd, KafkaProducerFixtures.initializedProducer(cmd), KafkaProducerBenchmarks.plainFlow)
  }
}

class AlpakkaKafkaPlainProducer extends BenchmarksBase() {
  private val prefix = "alpakka-kafka-plain-producer"

  it should "bench with small messages" in {
    val cmd = RunTestCommand(prefix, bootstrapServers, FilledTopic(2000 * factor, 100))
    runPerfTest(cmd, ReactiveKafkaProducerFixtures.flowFixture(cmd), ReactiveKafkaProducerBenchmarks.plainFlow)
  }

  it should "bench with normal messages" in {
    val cmd = RunTestCommand(prefix + "-normal-msg", bootstrapServers, FilledTopic(2000 * factor, 5000))
    runPerfTest(cmd, ReactiveKafkaProducerFixtures.flowFixture(cmd), ReactiveKafkaProducerBenchmarks.plainFlow)
  }

  it should "bench with normal messages written to 8 partitions" in {
    val cmd =
      RunTestCommand(prefix + "-normal-msg-8-partitions", bootstrapServers, FilledTopic(2000 * factor, 5000, numberOfPartitions = 8))
    runPerfTest(cmd, ReactiveKafkaProducerFixtures.flowFixture(cmd), ReactiveKafkaProducerBenchmarks.plainFlow)
  }
}

class ApacheKafkaTransactions extends BenchmarksBase() {
  it should "bench with small messages" in {
    val cmd = RunTestCommand("apache-kafka-transactions", bootstrapServers, FilledTopic(100 * factor, 100))
    runPerfTest(cmd,
                KafkaTransactionFixtures.initialize(cmd),
                KafkaTransactionBenchmarks.consumeTransformProduceTransaction(commitInterval = 100.milliseconds))
  }

  it should "bench with normal messages" in {
    val cmd = RunTestCommand("apache-kafka-transactions-normal-msg", bootstrapServers, FilledTopic(100 * factor, 5000))
    runPerfTest(cmd,
                KafkaTransactionFixtures.initialize(cmd),
                KafkaTransactionBenchmarks.consumeTransformProduceTransaction(commitInterval = 100.milliseconds))
  }
}

class AlpakkaKafkaTransactions extends BenchmarksBase() {
  it should "bench with small messages" in {
    val cmd = RunTestCommand("alpakka-kafka-transactions", bootstrapServers, FilledTopic(100 * factor, 100))
    runPerfTest(
      cmd,
      ReactiveKafkaTransactionFixtures.transactionalSourceAndSink(cmd, commitInterval = 100.milliseconds),
      ReactiveKafkaTransactionBenchmarks.consumeTransformProduceTransaction
    )
  }

  it should "bench with normal messages" in {
    val cmd = RunTestCommand("alpakka-kafka-transactions-normal-msg", bootstrapServers, FilledTopic(100 * factor, 5000))
    runPerfTest(
      cmd,
      ReactiveKafkaTransactionFixtures.transactionalSourceAndSink(cmd, commitInterval = 100.milliseconds),
      ReactiveKafkaTransactionBenchmarks.consumeTransformProduceTransaction
    )
  }
}
