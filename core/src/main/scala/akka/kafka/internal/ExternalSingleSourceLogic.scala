/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.internal

import akka.actor.{ActorRef, Terminated}
import akka.kafka.Subscriptions.{Assignment, AssignmentOffsetsForTimes, AssignmentWithOffset}
import akka.kafka.{ConsumerFailed, ManualSubscription}
import akka.stream.SourceShape
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage.{GraphStageLogic, OutHandler, StageLogging}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

private[kafka] abstract class ExternalSingleSourceLogic[K, V, Msg](
    val shape: SourceShape[Msg],
    val consumerActor: ActorRef,
    subscription: ManualSubscription
) extends GraphStageLogic(shape)
    with PromiseControl
    with MetricsControl
    with MessageBuilder[K, V, Msg]
    with StageLogging {

  override protected def logSource: Class[_] = classOf[SingleSourceLogic[K, V, Msg]]

  override def executionContext: ExecutionContext = materializer.executionContext
  override val consumerFuture: Future[ActorRef] = Future.successful(consumerActor)
  var sourceActor: StageActor = _
  var tps = Set.empty[TopicPartition]
  var buffer: Iterator[ConsumerRecord[K, V]] = Iterator.empty
  var requested = false
  var requestId = 0

  override def preStart(): Unit = {
    super.preStart()

    sourceActor = getStageActor {
      case (_, msg: KafkaConsumerActor.Internal.Messages[K, V]) =>
        // might be more than one in flight when we assign/revoke tps
        if (msg.requestId == requestId)
          requested = false
        // do not use simple ++ because of https://issues.scala-lang.org/browse/SI-9766
        if (buffer.hasNext) {
          buffer = buffer ++ msg.messages
        } else {
          buffer = msg.messages
        }
        pump()
      case (_, Terminated(ref)) if ref == consumerActor =>
        failStage(new ConsumerFailed())
    }
    sourceActor.watch(consumerActor)

    subscription match {
      case Assignment(topics, _) =>
        consumerActor.tell(KafkaConsumerActor.Internal.Assign(topics), sourceActor.ref)
        tps ++= topics
      case AssignmentWithOffset(topics, _) =>
        consumerActor.tell(KafkaConsumerActor.Internal.AssignWithOffset(topics), sourceActor.ref)
        tps ++= topics.keySet
      case AssignmentOffsetsForTimes(topics, _) =>
        consumerActor.tell(KafkaConsumerActor.Internal.AssignOffsetsForTimes(topics), sourceActor.ref)
        tps ++= topics.keySet
    }
  }

  @tailrec
  private def pump(): Unit =
    if (isAvailable(shape.out)) {
      if (buffer.hasNext) {
        val msg = buffer.next()
        push(shape.out, createMessage(msg))
        pump()
      } else if (!requested && tps.nonEmpty) {
        requestMessages()
      }
    }

  private def requestMessages(): Unit = {
    requested = true
    requestId += 1
    log.debug("Requesting messages, requestId: {}, partitions: {}", requestId, tps)
    consumerActor.tell(KafkaConsumerActor.Internal.RequestMessages(requestId, tps), sourceActor.ref)
  }

  setHandler(shape.out, new OutHandler {
    override def onPull(): Unit =
      pump()

    override def onDownstreamFinish(): Unit =
      performShutdown()
  })

  override def postStop(): Unit = {
    onShutdown()
    super.postStop()
  }

  override def performShutdown(): Unit =
    completeStage()

}
