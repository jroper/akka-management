/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.management.cluster.bootstrap.contactpoint

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.discovery.ServiceDiscovery.{Resolved, ResolvedTarget}
import akka.discovery.{Lookup, MockDiscovery}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.stream.ActorMaterializer
import akka.testkit.{SocketUtil, TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration._

class RemotingClusterBootstrapIntegrationSpec extends WordSpecLike with Matchers {

  "Cluster Bootstrap with remoting probing" should {

    var remotingPorts = Map.empty[String, Int]

    def config(id: String): Config = {
      val remotingPort = SocketUtil.temporaryServerAddress("127.0.0.1").getPort

      info(s"System [$id]:   remoting port: $remotingPort")

      remotingPorts = remotingPorts.updated(id, remotingPort)

      ConfigFactory.parseString(s"""
        akka {
          loglevel = INFO

          cluster.jmx.multi-mbeans-in-same-jvm = on

          # this can be referred to in tests to use the mock discovery implementation
          discovery.mock-dns.class = "akka.discovery.MockDiscovery"

          remote.netty.tcp.port = $remotingPort

          management {

            cluster.bootstrap {
              contact-point-discovery {
                discovery-method = mock-dns

                service-name = "remotingservice"
                port-name = "remoting2"
                protocol = "tcp2"

                service-namespace = "svc.cluster.local"

                stable-margin = 4 seconds
              }
              contact-point {
                probe-method = remoting
              }
            }
          }
        }
        """.stripMargin).withFallback(ConfigFactory.load())
    }

    val systemA = ActorSystem("System", config("A"))
    val systemB = ActorSystem("System", config("B"))
    val systemC = ActorSystem("System", config("C"))

    val clusterA = Cluster(systemA)
    val clusterB = Cluster(systemB)
    val clusterC = Cluster(systemC)

    val bootstrapA = ClusterBootstrap(systemA)
    val bootstrapB = ClusterBootstrap(systemB)
    val bootstrapC = ClusterBootstrap(systemC)

    // prepare the "mock DNS"
    val name = "remotingservice.svc.cluster.local"
    MockDiscovery.set(Lookup(name, Some("remoting2"), Some("tcp2")),
      () =>
        Future.successful(
          Resolved(name,
            List(
              ResolvedTarget(
                host = clusterA.selfAddress.host.get,
                port = remotingPorts.get("A"),
                address = Option(InetAddress.getByName(clusterA.selfAddress.host.get))
              ),
              ResolvedTarget(
                host = clusterB.selfAddress.host.get,
                port = remotingPorts.get("B"),
                address = Option(InetAddress.getByName(clusterB.selfAddress.host.get))
              ),
              ResolvedTarget(
                host = clusterC.selfAddress.host.get,
                port = remotingPorts.get("C"),
                address = Option(InetAddress.getByName(clusterC.selfAddress.host.get))
              )
            ))
      ))

    "start listening with the remote contact-points on 3 systems" in {
      def start(system: ActorSystem) = {
        implicit val sys = system
        implicit val mat = ActorMaterializer()(system)
        ClusterBootstrap(system)
      }

      start(systemA)
      start(systemB)
      start(systemC)
    }

    "join three DNS discovered nodes by forming new cluster (happy path)" in {
      bootstrapA.discovery.getClass should ===(classOf[MockDiscovery])

      bootstrapA.start()
      bootstrapB.start()
      bootstrapC.start()

      val pA = TestProbe()(systemA)
      clusterA.subscribe(pA.ref, classOf[MemberUp])

      pA.expectMsgType[CurrentClusterState]
      val up1 = pA.expectMsgType[MemberUp](30.seconds)
      info("" + up1)
    }

    "terminate all systems" in {
      try TestKit.shutdownActorSystem(systemA, 3.seconds)
      finally try TestKit.shutdownActorSystem(systemB, 3.seconds)
      finally TestKit.shutdownActorSystem(systemC, 3.seconds)
    }

  }

}