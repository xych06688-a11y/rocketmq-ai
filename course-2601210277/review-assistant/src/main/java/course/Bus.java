package course;
import com.alibaba.fastjson.JSON;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.apache.rocketmq.a2a.common.model.*;
import org.apache.rocketmq.a2a.common.util.RocketMQUtil;
import org.apache.rocketmq.client.apis.*;
import org.apache.rocketmq.client.apis.consumer.*;
import org.apache.rocketmq.client.apis.message.*;
import org.apache.rocketmq.client.apis.producer.*;
public final class Bus implements AutoCloseable {
    private final Config config;
    private final Map<String,String> topics;
    private final String replyTopic, supervisorGroup;
    private final Producer producer;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final List<AutoCloseable> consumers = new ArrayList<>();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    public Bus(Config config) throws Exception {
        this(config, Map.of("style",Config.TASK,"logic",Config.TASK,"security",Config.TASK,"summary",Config.TASK), Config.REPLY, "supervisor");
    }
    public Bus(Config config, Map<String,String> topics, String replyTopic, String supervisorGroup) throws Exception {
        this.config = config; this.topics=Map.copyOf(topics); this.replyTopic=replyTopic; this.supervisorGroup=supervisorGroup;
        Set<String> destinations=new HashSet<>(topics.values()); destinations.add(replyTopic);
        producer = RocketMQUtil.buildProducer(config.required("rocketmq.namespace"), config.required("rocketmq.endpoint"),
            config.required("rocketmq.accessKey"), config.required("rocketmq.secretKey"), destinations.toArray(String[]::new));
    }
    public void subscribeSession(String session) throws Exception {
        LitePushConsumer consumer = RocketMQUtil.buildLitePushConsumer(config.required("rocketmq.endpoint"),
            config.required("rocketmq.namespace"), config.required("rocketmq.accessKey"), config.required("rocketmq.secretKey"),
            replyTopic, config.group(supervisorGroup), view -> {
                try {
                    RocketMQResponse reply = JSON.parseObject(body(view), RocketMQResponse.class);
                    if (!session.equals(reply.getContextId()) || !session.equals(view.getLiteTopic().orElse(""))) return ConsumeResult.SUCCESS;
                    CompletableFuture<String> waiting = pending.get(reply.getRequestId());
                    if (waiting != null) waiting.complete(reply.getResponseBody());
                    trace("RECEIVE_REPLY", reply.getRequestId(), session, view.getMessageId().toString());
                    return ConsumeResult.SUCCESS;
                } catch (Exception e) { return ConsumeResult.FAILURE; }
            });
        consumers.add(consumer);
        consumer.subscribeLite(session);
    }
    public CompletableFuture<String> send(String role, String session, String input) {
        String requestId = session + "-" + role + "-" + UUID.randomUUID();
        CompletableFuture<String> result = new CompletableFuture<>();
        pending.put(requestId, result); // Register before sending: fast replies cannot be lost.
        result.orTimeout(120, TimeUnit.SECONDS).whenComplete((value, failure) -> pending.remove(requestId, result));
        try {
            RocketMQRequest envelope = RocketMQRequest.builder().requestId(requestId).destAgentTopic(topics.get(role))
                .workAgentResponseTopic(replyTopic).liteTopic(session).requestBody(Protocol.request(requestId, session, input)).build();
            Message message = provider.newMessageBuilder().setTopic(topics.get(role)).setTag(role).setKeys(requestId)
                .setBody(JSON.toJSONString(envelope).getBytes(StandardCharsets.UTF_8)).build();
            SendReceipt receipt = producer.send(message);
            trace("SEND_" + role.toUpperCase(), requestId, session, receipt.getMessageId().toString());
        } catch (Exception e) { result.completeExceptionally(e); }
        return result;
    }
    public void startWorker(String role, Model model) throws Exception {
        // Bounded response cache: duplicate delivery reuses the result within this process.
        Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<>() {
            protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 1000; }
        });
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(config.required("rocketmq.endpoint")).setNamespace(config.required("rocketmq.namespace"))
            .setCredentialProvider(new StaticSessionCredentialsProvider(config.required("rocketmq.accessKey"), config.required("rocketmq.secretKey")))
            .setRequestTimeout(java.time.Duration.ofSeconds(15)).build();
        // Configure the role filter at construction. SDK helper uses an immutable wildcard map.
        PushConsumer consumer = provider.newPushConsumerBuilder().setClientConfiguration(clientConfiguration)
            .setConsumerGroup(config.group(role))
            .setSubscriptionExpressions(Map.of(topics.get(role), new FilterExpression(role, FilterExpressionType.TAG)))
            .setMessageListener(view -> {
                try {
                    if (!role.equals(view.getTag().orElse(""))) return ConsumeResult.SUCCESS;
                    RocketMQRequest request = JSON.parseObject(body(view), RocketMQRequest.class);
                    if (request == null || request.getRequestId() == null || !request.getRequestId().startsWith(Config.ID + "-"))
                        return ConsumeResult.SUCCESS;
                    if (!replyTopic.equals(request.getWorkAgentResponseTopic()) || request.getLiteTopic() == null
                        || !request.getLiteTopic().startsWith(Config.ID + "-")) return ConsumeResult.SUCCESS;
                    trace("START_" + role.toUpperCase(), request.getRequestId(), request.getLiteTopic(), view.getMessageId().toString());
                    String response = cache.get(request.getRequestId());
                    if (response == null) {
                        response = Protocol.response(request.getRequestId(), request.getLiteTopic(), model.review(role, Protocol.input(request.getRequestBody())));
                        cache.put(request.getRequestId(), response);
                    }
                    RocketMQResponse envelope = RocketMQResponse.builder().requestId(request.getRequestId())
                        .contextId(request.getLiteTopic()).responseBody(response).stream(false).end(true).build();
                    Message reply = provider.newMessageBuilder().setTopic(replyTopic).setLiteTopic(request.getLiteTopic())
                        .setKeys(request.getRequestId()).setBody(JSON.toJSONString(envelope).getBytes(StandardCharsets.UTF_8)).build();
                    SendReceipt receipt = producer.send(reply);
                    trace("END_" + role.toUpperCase(), request.getRequestId(), request.getLiteTopic(), receipt.getMessageId().toString());
                    return ConsumeResult.SUCCESS;
                } catch (Exception e) {
                    System.err.println("WORKER_FAILURE role=" + role + " type=" + e.getClass().getSimpleName()+" message="+e.getMessage());
                    return ConsumeResult.FAILURE;
                }
            }).build();
        consumers.add(consumer);
    }
    private static String body(MessageView view) {
        var buffer = view.getBody(); byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void trace(String event, String requestId, String session, String messageId) {
        System.out.println(JSON.toJSONString(Map.of("time", java.time.Instant.now().toString(), "event", event,
            "requestId", requestId, "session", session, "messageId", messageId)));
    }
    public void close() throws Exception {
        Exception failure = null;
        for (AutoCloseable consumer : consumers) { try { consumer.close(); } catch (Exception e) { failure = e; } }
        try { producer.close(); } catch (Exception e) { failure = e; }
        pending.values().forEach(future -> future.cancel(true));
        if (failure != null) throw failure;
    }
}
