/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.javadsl;

import akka.actor.ActorSystem;
import akka.kafka.testkit.KafkaTestkitTestcontainersSettings;
import akka.kafka.testkit.javadsl.TestcontainersKafkaTest;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

// #testcontainers-settings
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestkitTestcontainersTest extends TestcontainersKafkaTest {

  private static final ActorSystem system = ActorSystem.create("TestkitTestcontainersTest");
  private static final Materializer materializer = ActorMaterializer.create(system);

  private static KafkaTestkitTestcontainersSettings testcontainersSettings =
      KafkaTestkitTestcontainersSettings.create(system)
          .withNumBrokers(3)
          .withInternalTopicsReplicationFactor(2)
          .withConfigureKafkaJava(
              brokerContainers ->
                  brokerContainers.forEach(
                      b -> b.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")));

  TestkitTestcontainersTest() {
    // this will only start a new cluster if it has not already been started.
    //
    // you must stop the cluster in the afterClass implementation if you want to create a cluster
    // per test class
    // using (TestInstance.Lifecycle.PER_CLASS)
    super(system, materializer, testcontainersSettings);
  }

  // ...

  // omit this implementation if you want the cluster to stay up for all your tests
  @AfterAll
  void afterClass() {
    TestcontainersKafkaTest.stopKafka();
  }
}
// #testcontainers-settings
