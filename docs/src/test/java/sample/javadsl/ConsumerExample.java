/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package sample.javadsl;


import akka.Done;
import akka.NotUsed;
import akka.actor.*;
import akka.japi.Pair;
import akka.kafka.*;
import akka.kafka.javadsl.Consumer;
import akka.kafka.javadsl.Producer;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import com.typesafe.config.Config;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

abstract class ConsumerExample {
  protected final ActorSystem system = ActorSystem.create("example");

  protected final Materializer materializer = ActorMaterializer.create(system);

  protected final int maxPartitions = 100;

  protected <T> Flow<T, T, NotUsed> business() {
    return Flow.create();
  }

  // #settings
  final Config config = system.settings().config();
  final ConsumerSettings<String, byte[]> consumerSettings =
      ConsumerSettings.create(config, new StringDeserializer(), new ByteArrayDeserializer())
          .withBootstrapServers("localhost:9092")
          .withGroupId("group1")
          .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
  // #settings

  protected final ProducerSettings<String, byte[]> producerSettings =
      ProducerSettings.create(system, new StringSerializer(), new ByteArraySerializer())
          .withBootstrapServers("localhost:9092");

  // #db
  static class DB {
    private final AtomicLong offset = new AtomicLong();

    public CompletionStage<Done> save(ConsumerRecord<String, byte[]> record) {
      System.out.println("DB.save: " + record.key());
      offset.set(record.offset());
      return CompletableFuture.completedFuture(Done.getInstance());
    }

    public CompletionStage<Long> loadOffset() {
      return CompletableFuture.completedFuture(offset.get());
    }

    public CompletionStage<Done> update(String key, byte[] data) {
      System.out.println("DB.update: " + key);
      return CompletableFuture.completedFuture(Done.getInstance());
    }
  }
  // #db

  // #rocket
  static class Rocket {
    public CompletionStage<Done> launch(String destination) {
      System.out.println("Rocket launched to " + destination);
      return CompletableFuture.completedFuture(Done.getInstance());
    }
  }
  // #rocket
}

// Consume messages and store a representation, including offset, in DB
class ExternalOffsetStorageExample extends ConsumerExample {
  public static void main(String[] args) {
    new ExternalOffsetStorageExample().demo();
  }

  public void demo() {
    // #plainSource
    final DB db = new DB();

    db.loadOffset().thenAccept(fromOffset -> {
      Consumer.plainSource(
        consumerSettings,
        Subscriptions.assignmentWithOffset(new TopicPartition("topic1", 0), fromOffset)
      ).mapAsync(1, record -> db.save(record))
          .runWith(Sink.ignore(), materializer);
    });
    // #plainSource
  }
}

// Consume messages at-most-once
class AtMostOnceExample extends ConsumerExample {
  public static void main(String[] args) {
    new AtMostOnceExample().demo();
  }

  public void demo() {
    // #atMostOnce
    final Rocket rocket = new Rocket();

    Consumer.atMostOnceSource(consumerSettings, Subscriptions.topics("topic1"))
      .mapAsync(1, record -> rocket.launch(record.key()))
      .runWith(Sink.ignore(), materializer);
    // #atMostOnce
  }
}

// Consume messages at-least-once
class AtLeastOnceExample extends ConsumerExample {
  public static void main(String[] args) {
    new AtLeastOnceExample().demo();
  }

  public void demo() {
    // #atLeastOnce
    final DB db = new DB();

    Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1"))
      .mapAsync(1, msg -> db.update(msg.record().key(), msg.record().value())
        .thenApply(done -> msg))
      .mapAsync(1, msg -> msg.committableOffset().commitJavadsl())
      .runWith(Sink.ignore(), materializer);
    // #atLeastOnce
  }
}

// Consume messages at-least-once, and commit in batches
class AtLeastOnceWithBatchCommitExample extends ConsumerExample {
  public static void main(String[] args) {
    new AtLeastOnceWithBatchCommitExample().demo();
  }

  public void demo() {
    // #atLeastOnceBatch
    final DB db = new DB();

    Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1"))
      .mapAsync(1, msg ->
        db.update(msg.record().key(), msg.record().value()).thenApply(done -> msg.committableOffset()))
      .batch(20,
        first -> ConsumerMessage.emptyCommittableOffsetBatch().updated(first),
        (batch, elem) -> batch.updated(elem))
        .mapAsync(3, c -> c.commitJavadsl())
      .runWith(Sink.ignore(), materializer);
    // #atLeastOnceBatch
  }
}

// Connect a Consumer to Producer
class ConsumerToProducerSinkExample extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerToProducerSinkExample().demo();
  }

  public void demo() {
    // #consumerToProducerSink
    Consumer.Control control =
        Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1", "topic2"))
          .map(msg ->
              new ProducerMessage.Message<String, byte[], ConsumerMessage.Committable>(
                  new ProducerRecord<>("targetTopic", msg.record().key(), msg.record().value()),
                  msg.committableOffset()
              )
          )
          .to(Producer.commitableSink(producerSettings))
          .run(materializer);
    // #consumerToProducerSink
    control.shutdown();
  }
}

// Connect a Consumer to Producer
class ConsumerToProducerFlowExample extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerToProducerFlowExample().demo();
  }

  public void demo() {
    // #consumerToProducerFlow
      Consumer.Control control =
          Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1"))
              .map(msg ->
                  new ProducerMessage.Message<String, byte[], ConsumerMessage.Committable>(
                      new ProducerRecord<>("topic2", msg.record().value()),
                      msg.committableOffset() // the passThrough
                  )
              )
              .via(Producer.flow(producerSettings))
              .mapAsync(producerSettings.parallelism(), result -> {
                  ConsumerMessage.Committable committable = result.message().passThrough();
                  return committable.commitJavadsl();
              })
              .to(Sink.ignore())
              .run(materializer);
      // #consumerToProducerFlow
  }
}

// Connect a Consumer to Producer, and commit in batches
class ConsumerToProducerWithBatchCommitsExample extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerToProducerWithBatchCommitsExample().demo();
  }

  public void demo() {
    // #consumerToProducerFlowBatch
    Source<ConsumerMessage.CommittableOffset, Consumer.Control> source =
      Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1"))
      .map(msg ->
          new ProducerMessage.Message<String, byte[], ConsumerMessage.CommittableOffset>(
              new ProducerRecord<>("topic2", msg.record().value()), msg.committableOffset()))
      .via(Producer.flow(producerSettings))
      .map(result -> result.message().passThrough());

      source.batch(20,
          first -> ConsumerMessage.emptyCommittableOffsetBatch().updated(first),
          (batch, elem) -> batch.updated(elem))
        .mapAsync(3, c -> c.commitJavadsl())
        .runWith(Sink.ignore(), materializer);
    // #consumerToProducerFlowBatch
  }
}

// Connect a Consumer to Producer, and commit in batches
class ConsumerToProducerWithBatchCommits2Example extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerToProducerWithBatchCommits2Example().demo();
  }

  public void demo() {
    Source<ConsumerMessage.CommittableOffset, Consumer.Control> source =
      Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1"))
      .map(msg ->
          new ProducerMessage.Message<String, byte[], ConsumerMessage.CommittableOffset>(
              new ProducerRecord<>("topic2", msg.record().value()), msg.committableOffset()))
      .via(Producer.flow(producerSettings))
      .map(result -> result.message().passThrough());

      // #groupedWithin
      source
        .groupedWithin(20, java.time.Duration.of(5, ChronoUnit.SECONDS))
        .map(group -> foldLeft(group))
        .mapAsync(3, c -> c.commitJavadsl())
      // #groupedWithin
        .runWith(Sink.ignore(), materializer);
  }

  // #groupedWithin

  private ConsumerMessage.CommittableOffsetBatch foldLeft(List<ConsumerMessage.CommittableOffset> group) {
    ConsumerMessage.CommittableOffsetBatch batch = ConsumerMessage.emptyCommittableOffsetBatch();
    for (ConsumerMessage.CommittableOffset elem: group) {
      batch = batch.updated(elem);
    }
    return batch;
  }
  //#groupedWithin
}

// Backpressure per partition with batch commit
class ConsumerWithPerPartitionBackpressure extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerWithPerPartitionBackpressure().demo();
  }

  public void demo() {
    // #committablePartitionedSource
    Consumer
      .committablePartitionedSource(consumerSettings, Subscriptions.topics("topic1"))
      .flatMapMerge(maxPartitions, Pair::second)
      .via(business())
      .batch(
          100,
          first -> ConsumerMessage.emptyCommittableOffsetBatch().updated(first.committableOffset()),
          (batch, elem) -> batch.updated(elem.committableOffset())
      )
      .mapAsync(3, x -> x.commitJavadsl())
      .runWith(Sink.ignore(), materializer);
    // #committablePartitionedSource
  }
}

class ConsumerWithIndependentFlowsPerPartition extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerWithIndependentFlowsPerPartition().demo();
  }

  public void demo() {
    // #committablePartitionedSource2
    Consumer.Control c =
      Consumer.committablePartitionedSource(consumerSettings, Subscriptions.topics("topic1"))
        .map(pair -> pair.second().via(business()).toMat(Sink.ignore(), Keep.both()).run(materializer))
        .mapAsyncUnordered(maxPartitions, (pair) -> pair.second()).to(Sink.ignore()).run(materializer);
    // #committablePartitionedSource2
  }
}

class ExternallyControlledKafkaConsumer extends ConsumerExample {
  public static void main(String[] args) {
    new ExternallyControlledKafkaConsumer().demo();
  }

  public void demo() {

    ActorRef self = null;
    // #consumerActor
    //Consumer is represented by actor
    ActorRef consumer = system.actorOf(KafkaConsumerActor.props(consumerSettings));

    //Manually assign topic partition to it
    Consumer
      .plainExternalSource(consumer, Subscriptions.assignment(new TopicPartition("topic1", 1)))
      .via(business())
      .runWith(Sink.ignore(), materializer);

    //Manually assign another topic partition
    Consumer
      .plainExternalSource(consumer, Subscriptions.assignment(new TopicPartition("topic1", 2)))
      .via(business())
      .runWith(Sink.ignore(), materializer);

    //Stop consumer actor
    consumer.tell(KafkaConsumerActor.stop(), self);
    // #consumerActor
  }
}

class RestartingConsumer extends ConsumerExample {
  public static void main(String[] args) { new RestartingConsumer().demo(); }

  public void demo() {
    //#restartSource
    RestartSource.withBackoff(
        java.time.Duration.of(3, ChronoUnit.SECONDS),
        java.time.Duration.of(30, ChronoUnit.SECONDS),
        0.2,
            () ->
                 Source.fromCompletionStage(
                      Consumer
                        .plainSource(consumerSettings, Subscriptions.topics("topic1"))
                        .via(business())
                        .watchTermination(
                                 (control, completionStage) ->
                                     completionStage.handle((res, ex) -> control.shutdown()).thenCompose(Function.identity())
                         )
                        .runWith(Sink.ignore(), materializer)
                 )
    );
    //#restartSource
  }
}

class RebalanceListenerCallbacksExample extends ConsumerExample {
  public static void main(String[] args) {
    new ExternallyControlledKafkaConsumer().demo();
  }

  // #withRebalanceListenerActor
  class RebalanceListener extends AbstractActor {

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(akka.kafka.TopicPartitionsAssigned.class, assigned -> {})
          .match(akka.kafka.TopicPartitionsRevoked.class, revoked -> {})
          .build();
    }
  }

  // #withRebalanceListenerActor

  public void demo(ActorSystem system) {
    // #withRebalanceListenerActor
    ActorRef listener = this.system.actorOf(Props.create(RebalanceListener.class));

    // pass in the listener callbacks into the subscription:
    Subscription sub = Subscriptions.topics("topic")
        .withRebalanceListener(listener);

    // use the subscription as usual:
    Consumer
      .plainSource(consumerSettings, sub);
    // #withRebalanceListenerActor
  }

}

class ConsumerMetricsExample extends ConsumerExample {
  public static void main(String[] args) {
    new ConsumerMetricsExample().demo();
  }

  public void demo() {
    // #consumerMetrics
    // run the stream to obtain the materialized Control value
    Consumer.Control control = Consumer
        .plainSource(consumerSettings, Subscriptions.assignment(new TopicPartition("topic1", 2)))
        .via(business())
        .to(Sink.ignore())
        .run(materializer);

    CompletionStage<Map<MetricName, Metric>> metrics = control.getMetrics();
    metrics.thenAccept(m -> System.out.println("Metrics: " + m));
    // #consumerMetrics
  }
}

// Shutdown via Consumer.Control
class ShutdownPlainSourceExample extends ConsumerExample {
  public static void main(String[] args) {
    new ExternalOffsetStorageExample().demo();
  }

  public void demo() {
    // #shutdownPlainSource
    final DB db = new DB();

    db.loadOffset().thenAccept(fromOffset -> {
      Consumer.Control control = Consumer.plainSource(
          consumerSettings,
          Subscriptions.assignmentWithOffset(new TopicPartition("topic1", 0), fromOffset)
      ).mapAsync(1, record -> db.save(record))
          .toMat(Sink.ignore(), Keep.left())
          .run(materializer);

      // Shutdown the consumer when desired
      control.shutdown();
    });
    // #shutdownPlainSource
  }
}

// Shutdown when batching commits
class ShutdownCommittableSourceExample extends ConsumerExample {
  public static void main(String[] args) {
    new AtLeastOnceExample().demo();
  }

  public void demo() {
    // #shutdownCommitableSource
    final Executor ec = Executors.newCachedThreadPool();
    final DB db = new DB();

    Consumer.DrainingControl<Done> control =
        Consumer.committableSource(consumerSettings, Subscriptions.topics("topic1"))
            .mapAsync(1, msg ->
                db.update(msg.record().key(), msg.record().value()).thenApply(done -> msg.committableOffset()))
            .batch(20,
                first -> ConsumerMessage.emptyCommittableOffsetBatch().updated(first),
                (batch, elem) -> batch.updated(elem))
            .mapAsync(3, c -> c.commitJavadsl())
            .toMat(Sink.ignore(), Keep.both())
            .mapMaterializedValue(Consumer::createDrainingControl)
            .run(materializer);

    control.drainAndShutdown(ec);
    // #shutdownCommitableSource
  }
}

