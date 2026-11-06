package com.example.support;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Topics, subscriptions, purge, publish and pull against the local Pub/Sub emulator, for integration tests.
 * The emulator is started by the developer (gcloud beta emulators pubsub start --host-port=localhost:8085); this class
 * only connects. Names created here carry the run id so two developers on one emulator do not collide, and
 * {@link #close()} deletes what this instance created.
 *
 * Usage in a base class:
 *   static PubSubTestSupport pubsub = new PubSubTestSupport(System.getenv().getOrDefault("PUBSUB_EMULATOR_HOST", "localhost:8085"), "test-project");
 *   String topic = pubsub.ensureTopic("orders");                 // "orders-<runId>"
 *   String sub = pubsub.ensureSubscription("orders-it", topic);
 *   pubsub.purge(sub);                                           // before each test
 *   List<PubsubMessage> got = pubsub.pull(sub, 1, Duration.ofSeconds(5));
 */
public final class PubSubTestSupport implements AutoCloseable {
    private final String project;
    private final String runId;
    private final ManagedChannel channel;
    private final TransportChannelProvider channelProvider;
    private final TopicAdminClient topics;
    private final SubscriptionAdminClient subscriptions;
    private final SubscriberStub subscriber;
    private final Set<TopicName> createdTopics = new LinkedHashSet<>();
    private final Set<SubscriptionName> createdSubscriptions = new LinkedHashSet<>();

    public PubSubTestSupport(String emulatorHost, String project) {
        this(emulatorHost, project, Long.toHexString(System.nanoTime()));
    }

    public PubSubTestSupport(String emulatorHost, String project, String runId) {
        this.project = project;
        this.runId = runId;
        this.channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
        this.channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        try {
            topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider).setCredentialsProvider(NoCredentialsProvider.create()).build());
            subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider).setCredentialsProvider(NoCredentialsProvider.create()).build());
            subscriber = GrpcSubscriberStub.create(SubscriberStubSettings.newBuilder()
                    .setTransportChannelProvider(channelProvider).setCredentialsProvider(NoCredentialsProvider.create()).build());
        } catch (IOException e) {
            throw new IllegalStateException("cannot connect to the Pub/Sub emulator at " + emulatorHost, e);
        }
    }

    public String runId() { return runId; }

    /** returns the full topic name; creates "<base>-<runId>" if it does not exist */
    public String ensureTopic(String base) {
        TopicName name = TopicName.of(project, base + "-" + runId);
        try { topics.getTopic(name); } catch (NotFoundException e) { topics.createTopic(name); createdTopics.add(name); }
        return name.toString();
    }

    /** returns the full subscription name on the given topic (full name); ack deadline 10s, no push */
    public String ensureSubscription(String base, String topicFullName) {
        SubscriptionName name = SubscriptionName.of(project, base + "-" + runId);
        try { subscriptions.getSubscription(name); }
        catch (NotFoundException e) { subscriptions.createSubscription(name, TopicName.parse(topicFullName), PushConfig.getDefaultInstance(), 10); createdSubscriptions.add(name); }
        return name.toString();
    }

    /** drops every message already in the subscription: seek to now, and a pull-and-ack sweep for emulators without seek */
    public void purge(String subscriptionFullName) {
        try {
            subscriptions.seek(SeekRequest.newBuilder().setSubscription(subscriptionFullName).setTime(Timestamps.fromMillis(System.currentTimeMillis())).build());
        } catch (RuntimeException ignored) {
            // older emulator builds reject seek; fall through to the sweep
        }
        List<ReceivedMessage> leftovers = pullOnce(subscriptionFullName, 1000);
        while (!leftovers.isEmpty()) { ack(subscriptionFullName, leftovers); leftovers = pullOnce(subscriptionFullName, 1000); }
    }

    /** publishes one message and waits for the emulator to accept it */
    public String publish(String topicFullName, String data, Map<String, String> attributes) {
        try {
            Publisher publisher = Publisher.newBuilder(topicFullName).setChannelProvider(channelProvider).setCredentialsProvider(NoCredentialsProvider.create()).build();
            try {
                return publisher.publish(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(data)).putAllAttributes(attributes).build()).get(10, TimeUnit.SECONDS);
            } finally { publisher.shutdown(); publisher.awaitTermination(5, TimeUnit.SECONDS); }
        } catch (Exception e) {
            throw new IllegalStateException("publish failed on " + topicFullName, e);
        }
    }

    /** pulls until at least {@code atLeast} messages arrived or the timeout passed; messages are acknowledged */
    public List<PubsubMessage> pull(String subscriptionFullName, int atLeast, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<PubsubMessage> out = new ArrayList<>();
        while (out.size() < atLeast && System.nanoTime() < deadline) {
            List<ReceivedMessage> batch = pullOnce(subscriptionFullName, 100);
            if (batch.isEmpty()) { sleep(200); continue; }
            ack(subscriptionFullName, batch);
            batch.forEach(m -> out.add(m.getMessage()));
        }
        return out;
    }

    private List<ReceivedMessage> pullOnce(String subscriptionFullName, int max) {
        PullResponse r = subscriber.pullCallable().call(PullRequest.newBuilder().setSubscription(subscriptionFullName).setMaxMessages(max).build());
        return r.getReceivedMessagesList();
    }

    private void ack(String subscriptionFullName, List<ReceivedMessage> messages) {
        List<String> ids = new ArrayList<>(); messages.forEach(m -> ids.add(m.getAckId()));
        subscriber.acknowledgeCallable().call(AcknowledgeRequest.newBuilder().setSubscription(subscriptionFullName).addAllAckIds(ids).build());
    }

    private static void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** deletes the subscriptions and topics this instance created, then closes the clients */
    @Override
    public void close() {
        for (SubscriptionName s : createdSubscriptions) { try { subscriptions.deleteSubscription(s); } catch (RuntimeException ignored) { } }
        for (TopicName t : createdTopics) { try { topics.deleteTopic(t); } catch (RuntimeException ignored) { } }
        subscriber.close(); subscriptions.close(); topics.close();
        channel.shutdownNow();
    }
}
