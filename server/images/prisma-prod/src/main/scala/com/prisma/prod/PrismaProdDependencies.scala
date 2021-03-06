package com.prisma.prod

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.prisma.akkautil.http.SimpleHttpClient
import com.prisma.api.ApiDependencies
import com.prisma.api.database.Databases
import com.prisma.api.project.{CachedProjectFetcherImpl, ProjectFetcher}
import com.prisma.api.schema.{CachedSchemaBuilder, SchemaBuilder}
import com.prisma.auth.AuthImpl
import com.prisma.deploy.DeployDependencies
import com.prisma.deploy.migration.migrator.{AsyncMigrator, Migrator}
import com.prisma.deploy.server.{ClusterAuthImpl, DummyClusterAuth}
import com.prisma.graphql.GraphQlClient
import com.prisma.messagebus._
import com.prisma.messagebus.pubsub.inmemory.InMemoryAkkaPubSub
import com.prisma.messagebus.pubsub.rabbit.RabbitAkkaPubSub
import com.prisma.messagebus.queue.inmemory.InMemoryAkkaQueue
import com.prisma.messagebus.queue.rabbit.RabbitQueue
import com.prisma.subscriptions.{SubscriptionDependencies, Webhook}
import com.prisma.subscriptions.protocol.SubscriptionProtocolV05.Responses.SubscriptionSessionResponseV05
import com.prisma.subscriptions.protocol.SubscriptionProtocolV07.Responses.SubscriptionSessionResponse
import com.prisma.subscriptions.protocol.SubscriptionRequest
import com.prisma.subscriptions.resolving.SubscriptionsManagerForProject.{SchemaInvalidated, SchemaInvalidatedMessage}
import com.prisma.workers.dependencies.WorkerDependencies
import com.prisma.workers.payloads.{JsonConversions, Webhook => WorkerWebhook}
import com.prisma.websocket.protocol.{Request => WebsocketRequest}
import play.api.libs.json.Json

case class PrismaProdDependencies()(implicit val system: ActorSystem, val materializer: ActorMaterializer)
    extends DeployDependencies
    with ApiDependencies
    with SubscriptionDependencies
    with WorkerDependencies {
  import com.prisma.prod.Converters._

  val rabbitUri: String = sys.env("RABBITMQ_URI")

  override implicit def self: PrismaProdDependencies = this

  override val databases        = Databases.initialize(config)
  override val apiSchemaBuilder = CachedSchemaBuilder(SchemaBuilder(), invalidationPubSub)
  override val projectFetcher: ProjectFetcher = {
    val fetcher = SingleServerProjectFetcher(projectPersistence)
    CachedProjectFetcherImpl(fetcher, invalidationPubSub)
  }

  override val migrator: Migrator = AsyncMigrator(clientDb, migrationPersistence, projectPersistence)
  override val clusterAuth = {
    sys.env.get("CLUSTER_PUBLIC_KEY") match {
      case Some(publicKey) if publicKey.nonEmpty => ClusterAuthImpl(publicKey)
      case _                                     => DummyClusterAuth()
    }
  }

  lazy val invalidationPubSub = {
    implicit val marshaller   = Conversions.Marshallers.FromString
    implicit val unmarshaller = Conversions.Unmarshallers.ToString
    RabbitAkkaPubSub[String](rabbitUri, "project-schema-invalidation", durable = true)
  }
  override lazy val invalidationPublisher                                              = invalidationPubSub
  override lazy val invalidationSubscriber: PubSubSubscriber[SchemaInvalidatedMessage] = invalidationPubSub.map((_: String) => SchemaInvalidated)

  lazy val theSssEventsPubSub = {
    implicit val marshaller   = Conversions.Marshallers.FromString
    implicit val unmarshaller = Conversions.Unmarshallers.ToString
    RabbitAkkaPubSub[String](rabbitUri, exchangeName = "sss-events", durable = true)
  }
  override lazy val sssEventsPubSub: PubSub[String]               = theSssEventsPubSub
  override lazy val sssEventsSubscriber: PubSubSubscriber[String] = theSssEventsPubSub

  // Note: The subscription stuff can be in memory
  lazy val requestsQueue: InMemoryAkkaQueue[WebsocketRequest]                 = InMemoryAkkaQueue[WebsocketRequest]()
  override lazy val requestsQueuePublisher: QueuePublisher[WebsocketRequest]  = requestsQueue
  override lazy val requestsQueueConsumer: QueueConsumer[SubscriptionRequest] = requestsQueue.map(Converters.websocketRequest2SubscriptionRequest)

  lazy val responsePubSub: InMemoryAkkaPubSub[String]                  = InMemoryAkkaPubSub[String]()
  override lazy val responsePubSubSubscriber: PubSubSubscriber[String] = responsePubSub

  lazy val converterResponse07ToString: SubscriptionSessionResponse => String = (response: SubscriptionSessionResponse) => {
    import com.prisma.subscriptions.protocol.ProtocolV07.SubscriptionResponseWriters._
    Json.toJson(response).toString
  }

  lazy val converterResponse05ToString: SubscriptionSessionResponseV05 => String = (response: SubscriptionSessionResponseV05) => {
    import com.prisma.subscriptions.protocol.ProtocolV05.SubscriptionResponseWriters._
    Json.toJson(response).toString
  }

  lazy val responsePubSubPublisherV05: PubSubPublisher[SubscriptionSessionResponseV05] = responsePubSub.map(converterResponse05ToString)
  lazy val responsePubSubPublisherV07: PubSubPublisher[SubscriptionSessionResponse]    = responsePubSub.map(converterResponse07ToString)

  override val keepAliveIntervalSeconds = 10

  override lazy val webhookPublisher =
    RabbitQueue.publisher[Webhook](rabbitUri, "webhooks")(reporter, Webhook.marshaller).asInstanceOf[QueuePublisher[Webhook]]
  override lazy val webhooksConsumer =
    RabbitQueue.consumer[WorkerWebhook](rabbitUri, "webhooks")(reporter, JsonConversions.webhookUnmarshaller).asInstanceOf[QueueConsumer[WorkerWebhook]]
  override lazy val httpClient    = SimpleHttpClient()
  override lazy val graphQlClient = GraphQlClient(sys.env.getOrElse("CLUSTER_ADDRESS", sys.error("env var CLUSTER_ADDRESS is not set")))

  override def apiAuth = AuthImpl
}
