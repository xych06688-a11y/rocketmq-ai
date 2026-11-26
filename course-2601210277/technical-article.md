# RocketMQ A2A 请求等待异常的资源清理分析

常何小丫 2601210277
源码分析与缺陷修复技术文章
实测日期 2026 年 11 月 17 日　更新日期 2026 年 11 月 26 日

RocketMQ A2A 把消息投递与应用中的请求等待关联起来。一次调用即使没有拿到正常结果，也必须结束它在本地的等待状态。本次分析发现 RocketMQUtil.getResult 只在正常返回时删除请求记录，线程中断或 Future 异常完成会留下条目。修复把清理放入 finally，并仅删除本次注册的 Future。新增三项回归测试后，补丁提交至 apache/rocketmq-ai 的 PR #12，已获得维护者批准并合并。

## 问题发生在消息与线程的交界处

消息队列负责异步传递请求和回复，调用方却可能希望等待一个结果。SDK 用 CompletableFuture 表示尚未完成的响应，再把它与响应标识关联。消费者收到对应回复后，可以找到这个 Future 并完成它，等待线程随之恢复执行。这个映射因此是请求生命周期的一部分：注册代表开始等待，正常返回或异常退出代表本次等待结束。

被分析的 MESSAGE_RESPONSE_MAP 按 namespace 分组，每组保存响应标识到 A2AResponseFuture 的映射。A2AResponseFuture 包装 CompletableFuture 以及反序列化类型信息。分析固定在基线提交 47c7c6610c2829fdd6aefcde66afc956aba5d8a7，避免把后来版本的行为混入修复前的判断。

## 可验证的缺陷与影响

原实现依次执行注册、等待 120 秒、移除、返回。Future.get 可以正常返回，也可能抛出 InterruptedException、ExecutionException 或 TimeoutException。只要等待抛出异常，后面的 remove 就不会执行；静态映射仍可引用本次包装对象及其 Future。重复失败请求可能积累失效条目。

这里证明的是请求映射残留，不是声称已经观察到生产环境 OOM。本次没有做长期堆内存压测，也没有测量每个残留条目的字节数。可靠的结论来自对控制流的分析，以及可以反复验证的映射清理断言。这个区分使修复目标明确，也避免用未经测量的性能数字夸大影响。

# 把清理与等待的生命周期绑定

以下代码摘自本次提交，省略外围参数校验和映射获取过程。关键对象是 A2AResponseFuture 包装实例，条件删除使用的值也是这个实例，而不是包装内部的 CompletableFuture。

## 修复前

```java
msgIdAndAsyncTypedMap.put(responseMessageId,
    new A2AResponseFuture(completableFuture, typeReference));
String result = completableFuture.get(120, TimeUnit.SECONDS);
msgIdAndAsyncTypedMap.remove(responseMessageId);
return result;
```

## 修复后

```java
A2AResponseFuture responseFuture =
    new A2AResponseFuture(completableFuture, typeReference);
msgIdAndAsyncTypedMap.put(responseMessageId, responseFuture);
try {
    return completableFuture.get(120, TimeUnit.SECONDS);
} finally {
    msgIdAndAsyncTypedMap.remove(
        responseMessageId, responseFuture);
}
```

finally 会在正常返回和这些异常退出路径上执行，因此清理不再依赖结果是否成功。异常仍按原来的方法契约向上抛出，没有被转换为成功结果，也没有改变 120 秒等待上限。finally 并不保证进程被强制终止时仍执行；本修复处理的是方法正常运行中的退出路径。

## 为什么按键和值一起删除

如果另一个调用在相同响应标识下写入了新的包装对象，旧调用结束时仅按键删除，可能把新记录一起清掉。ConcurrentHashMap 的 remove(key, value) 仅在当前值匹配时删除，避免旧等待者清理已经替换的条目。它保护的是清理操作，不代表整个请求注册流程已获得唯一性保证，也没有为重复响应标识建立完整协议。

本次补丁没有移除空的 namespace 容器，也没有取消远端任务。映射清理、Future 取消、远端任务取消是三个不同动作。请求超时后，本地不再等待，并不能据此推断消息未送达或工作 Agent 未执行。需要重试时仍应考虑幂等和迟到回复。

# 用回归测试证明异常退出后的清理

三个测试都直接调用真实的 RocketMQUtil.getResult，不启动 Broker，也不调用模型。测试为每次执行生成独立 namespace，创建等待线程，并先确认请求已注册，然后触发预定完成方式。这样测试聚焦在本地请求状态，不依赖外部网络、云配额或消息投递时序。

## 触发方式与断言

场景 | 触发方式 | 核心断言
正常完成 | complete("response") | 返回指定文本，映射不含请求
线程中断 | waiter.interrupt() | 传播 InterruptedException，映射清空
异常完成 | completeExceptionally(...) | 传播 ExecutionException 并保留原始原因，映射清空

注册等待采用最长五秒的轮询，而不是假设线程启动后已经进入 getResult。完成等待使用 CountDownLatch 并设置时限。每个测试最终都会中断并回收等待线程、移除自己的 namespace，避免测试自身的状态污染后续用例。断言既检查等待结果或异常类型，也检查请求条目是否还存在。

## 修改前后的实际结果

在原实现上，正常完成测试通过，中断与异常完成两项测试失败，失败点都是请求仍留在 pending map。应用修复后，三项全部通过；Maven verify 与 Checkstyle 通过。原始日志分别保存在贡献材料的 pr-before.log 和 pr-after.log 中，公开 PR 也说明了相同的验证结果。

固定 120 秒的真实超时没有直接等待到期测试，条件删除在并发替换场景下也没有单独回归用例。因此测试覆盖结论是三种已执行场景；超时清理来自 finally 控制流分析，不能写成“超时实测通过”。后续可在不扩大生产接口的前提下引入可控等待策略，单独验证超时和替换场景。

## 本地复核命令

```java
git clone https://github.com/apache/rocketmq-ai.git
cd rocketmq-ai
git checkout c94fc997cd0373fcce0fbe626cf86d28960be65b
mvn -B -f rocketmq-a2a/pom.xml verify
```

使用 JDK 17 与 Maven 3.9.x。依赖下载慢时，可使用课程代码包的 maven-settings.xml，并通过 -s 指定镜像设置。测试无需 RocketMQ 或模型凭据；首次构建仍需要访问 Maven 依赖仓库。

# 从单个修复到多智能体工程实践

## 清理修复的边界

本次修复只保证已注册等待记录在方法退出时被正确清理。另一类独立问题是先发送请求、后注册等待：回复可能在记录创建之前到达。finally 不能补回已经错过的回复，所以 PR 明确排除了这个竞态。将它混在同一个小补丁里，会改变调用路径和测试范围，使维护者难以单独判断清理逻辑是否正确。

课程 Review 助手在自编通信层采用先注册 Future、后发送请求，并按 session 与 requestId 双重关联结果。三类审查结果齐备后才触发 SummaryAgent。它说明了 SDK 缺陷分析如何帮助应用设计，但应用的保护逻辑并不意味着上游 SDK 的独立竞态也已修复。

对于长时间运行的 Agent 服务，可以进一步观察 pending 请求数量、请求年龄、超时计数和迟到回复数量，并把日志中的 requestId 与 RocketMQ 消息 Key 关联。这里提出的是后续观测建议，本次并未部署这些指标，也未据此宣称吞吐提升。RocketMQ 轨迹说明消息发送和消费过程，本地 Future 状态说明调用方等待过程，两类证据应分别核对。

## 社区提交与复核记录

PR #12 于 2026 年 11 月 17 日提交到 apache/rocketmq-ai，改动涉及 SDK 实现、测试依赖与回归测试三个文件。维护者 ShannonDing 于北京时间 2026 年 11 月 23 日 22:53:33 给出 APPROVED；22:53:52 合并，合并提交为 c94fc997cd0373fcce0fbe626cf86d28960be65b。2026 年 11 月 26 日再次核验该状态。审核没有附加文字意见，因此不编造具体评语。

本次改动的工程价值在于把“结束等待”与“释放登记状态”保持一致，再用能够在旧版本上失败的测试证明缺陷。合并记录说明维护者接受了该补丁；它不替代应用在自身网络、负载和故障条件下的验证。

## 参考源码与可核验材料

1. 官方 PR 与 Review 记录
https://github.com/apache/rocketmq-ai/pull/12
2. 固定合并提交
https://github.com/apache/rocketmq-ai/commit/c94fc997cd0373fcce0fbe626cf86d28960be65b
3. 分析文件
rocketmq-a2a/src/main/java/org/apache/rocketmq/a2a/common/util/RocketMQUtil.java
4. 回归测试
rocketmq-a2a/src/test/java/org/apache/rocketmq/a2a/common/util/RocketMQUtilTest.java
5. 本地材料
contribution/fix-pending-response.patch、pr-before.log、pr-after.log 及 PR-12-status-20261126.json
