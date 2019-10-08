/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.scaladsl

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.ExecutionContexts
import akka.kafka.{ConsumerSettings, KafkaConsumerActor}
import akka.kafka.Metadata.{BeginningOffsets, GetBeginningOffsets}
import akka.pattern.ask
import akka.util.Timeout
import org.apache.kafka.common.TopicPartition

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class MetadataClient private (consumerActor: ActorRef, timeout: Timeout)(implicit ec: ExecutionContext) {

  def getBeginningOffsets(partitions: Set[TopicPartition]): Future[Map[TopicPartition, Long]] =
    (consumerActor ? GetBeginningOffsets(partitions))(timeout)
      .mapTo[BeginningOffsets]
      .map(_.response)
      .flatMap {
        case Success(res) => Future.successful(res)
        case Failure(e) => Future.failed(e)
      }(ExecutionContexts.sameThreadExecutionContext)

  def getBeginningOffsetForPartition(partition: TopicPartition): Future[Long] =
    getBeginningOffsets(Set(partition))
      .map(beginningOffsets => beginningOffsets(partition))

  def stop(): Unit =
    consumerActor ! KafkaConsumerActor.Stop
}

object MetadataClient {

  private val consumerActors = scala.collection.mutable.Map[ConsumerSettings[Any, Any], ActorRef]()

  def create(consumerActor: ActorRef, timeout: Timeout)(implicit ec: ExecutionContext): MetadataClient =
    new MetadataClient(consumerActor, timeout)

  def create[K, V](
      consumerSettings: ConsumerSettings[K, V],
      timeout: Timeout
  )(implicit system: ActorSystem, ec: ExecutionContext): MetadataClient = {
    val consumerActor = getConsumerActor(system, consumerSettings)
    new MetadataClient(consumerActor, timeout)
  }

  private def getConsumerActor[K, V](system: ActorSystem, consumerSettings: ConsumerSettings[K, V]) = {
    val consumerActor = consumerActors.get(consumerSettings.asInstanceOf[ConsumerSettings[Any, Any]])
    if (consumerActor.isEmpty) {
      val newConsumerActor = system.actorOf(KafkaConsumerActor.props(consumerSettings))
      consumerActors.put(consumerSettings.asInstanceOf[ConsumerSettings[Any, Any]], newConsumerActor)
      newConsumerActor
    } else {
      consumerActor.get
    }
  }
}
