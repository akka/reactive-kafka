/*
 * Copyright (C) 2014 - 2016 Softwaremill <https://softwaremill.com>
 * Copyright (C) 2016 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.scaladsl

import java.nio.charset.StandardCharsets

import akka.Done
import akka.kafka._
import akka.kafka.testkit.internal.TestFrameworkInterface
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl._
import akka.kafka.testkit.scaladsl.{KafkaSpec, TestcontainersKafkaLike}
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import io.confluent.kafka.schemaregistry.avro.AvroCompatibilityLevel
import org.apache.avro.util.Utf8
import org.apache.kafka.common.TopicPartition
import org.scalatest.concurrent.IntegrationPatience
// #imports
import io.confluent.kafka.serializers.{AbstractKafkaAvroSerDeConfig, KafkaAvroDeserializer}
import org.apache.avro.specific.SpecificRecord
// #imports
import net.manub.embeddedkafka.schemaregistry.{EmbeddedKafka, EmbeddedKafkaConfigImpl}
import org.apache.avro.specific.SpecificRecordBase
import org.apache.avro.{AvroRuntimeException, Schema}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
// #imports
import org.apache.kafka.common.serialization._
// #imports
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{FlatSpecLike, Matchers}
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.jdk.CollectionConverters._
import scala.collection.immutable
import scala.concurrent.duration._

// #spray-imports
import spray.json._
// #spray-imports

// #spray-deser

final case class SampleData(name: String, value: Int)

object SampleDataSprayProtocol extends DefaultJsonProtocol {
  implicit val sampleDataProtocol: RootJsonFormat[SampleData] = jsonFormat2(SampleData)
}

import SampleDataSprayProtocol._
// #spray-deser

class SerializationSpec extends DocsSpecBase with TestcontainersKafkaLike {

  "Deserialization in map" should "be documented" in assertAllStagesStopped {
    val group = createGroupId()
    val topic = createTopic()

    val sample = SampleData("Viktor", 54)
    val samples = List(sample, sample, sample)

    awaitProduce(
      produceString(topic, List("{faulty JSON data") ++ samples.map(_.toJson.compactPrint))
    )

    val consumerSettings = consumerDefaults.withGroupId(group)

    // #spray-deser

    val resumeOnParsingException = ActorAttributes.supervisionStrategy {
      case _: spray.json.JsonParser.ParsingException => Supervision.Resume
      case _ => Supervision.stop
    }

    val consumer = Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic))
      .map { consumerRecord =>
        val value = consumerRecord.value()
        val sampleData = value.parseJson.convertTo[SampleData]
        sampleData
      }
      .withAttributes(resumeOnParsingException)
      // #spray-deser
      .take(samples.size.toLong)
      // #spray-deser
      .toMat(Sink.seq)(DrainingControl.apply)
      .run()
    // #spray-deser

    consumer.isShutdown.futureValue should be(Done)
    consumer.drainAndShutdown().futureValue should be(samples)
  }

  "Protobuf use" should "be documented" in assertAllStagesStopped {
    // #protobuf-imports
    // the Protobuf generated class
    import docs.scaladsl.proto.Order

    // #protobuf-imports
    val group = createGroupId()
    val topic = createTopic()

    val sample = Order(id = "789465")
    val samples = List(sample, sample, sample)

    // #protobuf-serializer
    val producerSettings: ProducerSettings[String, Array[Byte]] = // ...
      // #protobuf-serializer
      producerDefaults(new StringSerializer(), new ByteArraySerializer())

    // #protobuf-serializer

    val producerCompletion =
      Source(samples)
        .map(order => new ProducerRecord(topic, order.id, order.toByteArray))
        .runWith(Producer.plainSink(producerSettings))
    // #protobuf-serializer

    // #protobuf-deserializer
    val resumeOnParsingException = ActorAttributes.supervisionStrategy {
      case _: com.google.protobuf.InvalidProtocolBufferException => Supervision.Resume
      case _ => Supervision.stop
    }

    val consumerSettings: ConsumerSettings[String, Array[Byte]] = // ...
      // #protobuf-deserializer
      consumerDefaults(new StringDeserializer, new ByteArrayDeserializer).withGroupId(group)
    // #protobuf-deserializer

    val consumer = Consumer
      .plainSource(consumerSettings, Subscriptions.topics(topic))
      .map { consumerRecord =>
        Order.parseFrom(consumerRecord.value())
      }
      .withAttributes(resumeOnParsingException)
      // #protobuf-deserializer
      .take(samples.size.toLong)
      // #protobuf-deserializer
      .toMat(Sink.seq)(DrainingControl.apply)
      .run()
    // #protobuf-deserializer

    producerCompletion.futureValue shouldBe Done
    consumer.isShutdown.futureValue should be(Done)
    consumer.drainAndShutdown().futureValue should be(samples)
  }

}
