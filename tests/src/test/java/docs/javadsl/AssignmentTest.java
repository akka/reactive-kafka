/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.javadsl;

import akka.Done;
import akka.actor.ActorSystem;
import akka.kafka.AutoSubscription;
import akka.kafka.KafkaPorts;
import akka.kafka.ManualSubscription;
import akka.kafka.ProducerMessage;
import akka.kafka.Subscriptions;
import akka.kafka.javadsl.Consumer;
// #testkit
import akka.kafka.testkit.javadsl.EmbeddedKafkaJunit4Test;
// #testkit
import akka.kafka.javadsl.Producer;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
// #testkit
import akka.testkit.javadsl.TestKit;
// #testkit
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
// #testkit
import org.junit.AfterClass;
import org.junit.Test;
// #testkit

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;

// #testkit

public class AssignmentTest extends EmbeddedKafkaJunit4Test {

  private static final ActorSystem sys = ActorSystem.create("AssignmentTest");
  private static final Materializer mat = ActorMaterializer.create(sys);

  public AssignmentTest() {
    super(sys, mat, KafkaPorts.AssignmentTest());
  }

  // #testkit

  @Test
  public void mustConsumeFromTheSpecifiedSingleTopic() throws Exception {
    final String topic = createTopic(0, 1, 1);
    final String group = createGroupId(0);
    final Integer totalMessages = 100;
    final CompletionStage<Done> producerCompletion =
        Source.range(1, totalMessages)
            .map(msg -> new ProducerRecord<>(topic, 0, DefaultKey(), msg.toString()))
            .runWith(Producer.plainSink(producerDefaults()), mat);

    resultOf(producerCompletion);

    // #single-topic
    final AutoSubscription subscription = Subscriptions.topics(topic);
    final Source<ConsumerRecord<String, String>, Consumer.Control> consumer =
        Consumer.plainSource(consumerDefaults().withGroupId(group), subscription);
    // #single-topic

    final Integer receivedMessages =
        resultOf(
                consumer
                    .takeWhile(m -> Integer.valueOf(m.value()) < totalMessages, true)
                    .runWith(Sink.seq(), mat))
            .size();

    assertEquals(totalMessages, receivedMessages);
  }

  @Test
  public void mustConsumeFromTheSpecifiedTopicPattern() throws Exception {
    final List<String> topics = Arrays.asList(createTopic(9001, 1, 1), createTopic(9002, 1, 1));
    final String group = createGroupId(0);
    final Integer totalMessages = 100;
    final CompletionStage<Done> producerCompletion =
        Source.range(1, totalMessages)
            .map(
                msg ->
                    ProducerMessage.multi(
                        topics
                            .stream()
                            .map(t -> new ProducerRecord<>(t, 0, DefaultKey(), msg.toString()))
                            .collect(Collectors.toList())))
            .via(Producer.flexiFlow(producerDefaults()))
            .runWith(Sink.ignore(), mat);

    resultOf(producerCompletion);

    // #topic-pattern
    final String pattern = "topic-900[1|2]-[0-9]+";
    final AutoSubscription subscription = Subscriptions.topicPattern(pattern);
    final Source<ConsumerRecord<String, String>, Consumer.Control> consumer =
        Consumer.plainSource(consumerDefaults().withGroupId(group), subscription);
    // #topic-pattern

    int expectedTotal = totalMessages * topics.size();
    final Integer receivedMessages =
        resultOf(consumer.take(expectedTotal).runWith(Sink.seq(), mat)).size();

    assertEquals(expectedTotal, (int) receivedMessages);
  }

  @Test
  public void mustConsumeFromTheSpecifiedPartition() throws Exception {
    final String topic = createTopic(2, 2, 1);
    final Integer totalMessages = 100;
    final CompletionStage<Done> producerCompletion =
        Source.range(1, totalMessages)
            .map(
                msg -> {
                  final Integer partition = msg % 2;
                  return new ProducerRecord<>(topic, partition, DefaultKey(), msg.toString());
                })
            .runWith(Producer.plainSink(producerDefaults()), mat);

    resultOf(producerCompletion);

    // #assingment-single-partition
    final Integer partition = 0;
    final ManualSubscription subscription =
        Subscriptions.assignment(new TopicPartition(topic, partition));
    final Source<ConsumerRecord<String, String>, Consumer.Control> consumer =
        Consumer.plainSource(consumerDefaults(), subscription);
    // #assingment-single-partition

    final CompletionStage<List<Integer>> consumerCompletion =
        consumer
            .take(totalMessages / 2)
            .map(msg -> Integer.valueOf(msg.value()))
            .runWith(Sink.seq(), mat);
    final List<Integer> messages = resultOf(consumerCompletion);
    messages.forEach(m -> assertEquals(0, m % 2));
  }

  @Test
  public void mustConsumeFromTheSpecifiedPartitionAndOffset() throws Exception {
    final String topic = createTopic(3, 1, 1);
    final Integer totalMessages = 100;
    final CompletionStage<Done> producerCompletion =
        Source.range(1, totalMessages)
            .map(msg -> new ProducerRecord<>(topic, 0, DefaultKey(), msg.toString()))
            .runWith(Producer.plainSink(producerDefaults()), mat);

    resultOf(producerCompletion);

    // #assingment-single-partition-offset
    final Integer partition = 0;
    final long offset = totalMessages / 2;
    final ManualSubscription subscription =
        Subscriptions.assignmentWithOffset(new TopicPartition(topic, partition), offset);
    final Source<ConsumerRecord<String, String>, Consumer.Control> consumer =
        Consumer.plainSource(consumerDefaults(), subscription);
    // #assingment-single-partition-offset

    final CompletionStage<List<Long>> consumerCompletion =
        consumer.take(totalMessages / 2).map(ConsumerRecord::offset).runWith(Sink.seq(), mat);
    final List<Long> messages = resultOf(consumerCompletion);
    IntStream.range(0, (int) offset).forEach(idx -> assertEquals(idx, messages.get(idx) - offset));
  }

  @Test
  public void mustConsumeFromTheSpecifiedPartitionAndTimestamp() throws Exception {
    final String topic = createTopic(4, 1, 1);
    final Integer totalMessages = 100;
    final CompletionStage<Done> producerCompletion =
        Source.range(1, totalMessages)
            .map(
                msg ->
                    new ProducerRecord<>(
                        topic, 0, System.currentTimeMillis(), DefaultKey(), msg.toString()))
            .runWith(Producer.plainSink(producerDefaults()), mat);

    resultOf(producerCompletion);

    // #assingment-single-partition-timestamp
    final Integer partition = 0;
    final Long now = System.currentTimeMillis();
    final Long messagesSince = now - 5000;
    final ManualSubscription subscription =
        Subscriptions.assignmentOffsetsForTimes(
            new TopicPartition(topic, partition), messagesSince);
    final Source<ConsumerRecord<String, String>, Consumer.Control> consumer =
        Consumer.plainSource(consumerDefaults(), subscription);
    // #assingment-single-partition-timestamp

    final CompletionStage<List<Long>> consumerCompletion =
        consumer
            .takeWhile(m -> Integer.valueOf(m.value()) < totalMessages, true)
            .map(ConsumerRecord::timestamp)
            .runWith(Sink.seq(), mat);
    final long oldMessages =
        resultOf(consumerCompletion).stream().map(t -> t - now).filter(t -> t > 5000).count();
    assertEquals(0, oldMessages);
  }

  // #testkit
  @AfterClass
  public static void afterClass() {
    TestKit.shutdownActorSystem(sys);
  }
}
// #testkit
