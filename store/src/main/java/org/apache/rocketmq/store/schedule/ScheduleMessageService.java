/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.schedule;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.common.ConfigManager;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.running.RunningStats;
import org.apache.rocketmq.store.ConsumeQueue;
import org.apache.rocketmq.store.ConsumeQueueExt;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.MessageStore;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;
import org.apache.rocketmq.store.SelectMappedBufferResult;
import org.apache.rocketmq.store.config.StorePathConfigHelper;

/**
 * 定时消息机制
 *
 * 定时消息是值消息发送到Broker后，并不立即被消费者消费而是要等到特定的时间后才能被消费
 *
 *
 * RocketMQ并不支持任意的时间精度，如果要支持任意时间精度的定时调度，不可避免地要在Broker层做消息排序(可以参考ScheduledExecutorService的实现原理)
 * 再加上持久化方面的考量，将不可避免地带来具大地性能消耗，所以RocketMQ只支持特定级别地延迟消息
 *
 * 消息重试正是借助定时任务实现地，在将消息存入commitlog文件之前需要判断消息的重试次数，如果大于0，则会将消息的主题设置为SCHEDULE_TOPIC_XXX
 *
 * RocketMQ的定时消息实现类为ScheduleMessageService，该类的实例在DefaultMessageStore创建
 * 通过在DefaultMessageStore中调用load方法加载并调用start方法进行启动
 *
 *
 * 定时消息实现
 * 1.消息消费者发送消息，如果发送的消息的delayLevel大于0，则改变消息主题为SCHEDULE_TOPIC_XXXX，消息队列为delayQueue-1
 * 2.消息由commitlog转发到消息消费队列SCHEDULE_TOPIC_XXXX的消息消费队列0
 * 3.定时任务Time每隔1S根据上次拉取偏移量从消费队列中取出所有消息
 * 4.根据消息的物理偏移量与消息大小从CommitLog拉取消息
 * 5.根据消息属性重新创建消息，并恢复主题topicA,原队列ID，清除delayLevel属性，存入commitLog
 * 6.转发到原主题topicA的消息消费队列，供消息消费者消费
 */
public class ScheduleMessageService extends ConfigManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    /**
     * 第一次调度时延迟的时间，默认为1S
     */
    private static final long FIRST_DELAY_TIME = 1000L;

    /**
     * 每一延时级别调度一次后延迟该时间间隔后再放入调度池
     */
    private static final long DELAY_FOR_A_WHILE = 100L;

    /**
     * 发送异常后延迟该时间后再继续参与调度
     */
    private static final long DELAY_FOR_A_PERIOD = 10000L;

    /**
     * 延迟级别
     */
    private final ConcurrentMap<Integer /* level */, Long/* delay timeMillis */> delayLevelTable =
        new ConcurrentHashMap<Integer, Long>(32);
    /**
     * 延迟级别消息消费进度
     */
    private final ConcurrentMap<Integer /* level */, Long/* offset */> offsetTable =
        new ConcurrentHashMap<Integer, Long>(32);

    /**
     * 默认消息存储器
     */
    private final DefaultMessageStore defaultMessageStore;

    private final AtomicBoolean started = new AtomicBoolean(false);

    private Timer timer;

    private MessageStore writeMessageStore;

    /**
     * messageDelayLevel中最大消息延迟级别
     */
    private int maxDelayLevel;

    /**
     * ScheduleMessageService方法的调用顺序：构造方法->load()->start()方法
     * @param defaultMessageStore
     */
    public ScheduleMessageService(final DefaultMessageStore defaultMessageStore) {
        this.defaultMessageStore = defaultMessageStore;
        this.writeMessageStore = defaultMessageStore;
    }

    public static int queueId2DelayLevel(final int queueId) {
        return queueId + 1;
    }

    public static int delayLevel2QueueId(final int delayLevel) {
        return delayLevel - 1;
    }

    /**
     * @param writeMessageStore
     *     the writeMessageStore to set
     */
    public void setWriteMessageStore(MessageStore writeMessageStore) {
        this.writeMessageStore = writeMessageStore;
    }

    public void buildRunningStats(HashMap<String, String> stats) {
        Iterator<Map.Entry<Integer, Long>> it = this.offsetTable.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> next = it.next();
            int queueId = delayLevel2QueueId(next.getKey());
            long delayOffset = next.getValue();
            long maxOffset = this.defaultMessageStore.getMaxOffsetInQueue(TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, queueId);
            String value = String.format("%d,%d", delayOffset, maxOffset);
            String key = String.format("%s_%d", RunningStats.scheduleMessageOffset.name(), next.getKey());
            stats.put(key, value);
        }
    }

    private void updateOffset(int delayLevel, long offset) {
        this.offsetTable.put(delayLevel, offset);
    }

    public long computeDeliverTimestamp(final int delayLevel, final long storeTimestamp) {
        Long time = this.delayLevelTable.get(delayLevel);
        if (time != null) {
            return time + storeTimestamp;
        }

        return storeTimestamp + 1000;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            super.load();
            this.timer = new Timer("ScheduleMessageTimerThread", true);


            /**
             * start()根据延迟级别创建对应的定时任务，启动定时任务持久化延迟消息队列进度存储
             *
             * 根据延迟队列创建定时任务，遍历延迟级别，根据延迟级别level从offsetTable中获取消费队列的消费进度
             * 如果不存在，则使用0.也就是说每一个延迟级别对应一个消息消费队列。
             *
             * 然后创建定时任务，每一个定时任务第一次启动时默认延迟1S先执行一次定时任务
             * 第二次调度开始才使用相应的延迟时间。延迟级别与消息消费队列的映射关系为： 消息队列ID = 延迟级别 - 1
             *
             */
            for (Map.Entry<Integer, Long> entry : this.delayLevelTable.entrySet()) {
                Integer level = entry.getKey();
                Long timeDelay = entry.getValue();
                Long offset = this.offsetTable.get(level);
                if (null == offset) {
                    offset = 0L;
                }

                if (timeDelay != null) {
                    this.timer.schedule(new DeliverDelayedMessageTimerTask(level, offset), FIRST_DELAY_TIME);
                }
            }

            /**
             * 定时消息的第一个设计关键点，定时消息单独一个主题：SCHEDULE_TOPIC_XXX
             * 该主题下队列数量等于MessageStoreConfig#messageDelayLevel配置的延迟级别数量
             * 其对应关系为queueId等于延迟级别减少1
             *
             * ScheduleMessageService为每一个延迟级别创建一个定时Timer根据延迟级别对应的延迟时间进行延迟调度
             *
             * 在消息发送时，如果消息的延迟级别delayLevel大于0，将消息的原主题名称、队列ID存入消息的属性中
             * 然后改变消息的主题、队列为延迟主题与延迟主题所属队列，消息将最终转发到延迟队列的消费队列
             *
             *
             * 定时消息的第二个设计关键点：消息存储时如果消息的延迟级别属性delayLevel大于0，则会备份原主题，原队列到消息属性中
             * 其键分别为PROPERTY_REAL_TOPIC,PROPERTY_REAL_QUEUE_ID，通过为不同的延迟级别创建不同的调度任务
             * 当时间到达后执行调度任务，调度任务主要就是根据延迟拉取消息消费进度从延迟队列中拉取消息，然后从commitLog中加载完整消息
             * 清除延迟级别属性并且恢复原先的主题，队列，再次创建一条新的消息存入到commitLog中，并转发到消息消费队列供消息消费者消费
             */

            /**
             * 创建定时任务，每隔10S持久化一次延迟队列的消息消费进度(延迟消息进度)
             * 持久化频率可以通过flushDelayOffsetInerval配置属性进行配置
             */
            this.timer.scheduleAtFixedRate(new TimerTask() {

                @Override
                public void run() {
                    try {
                        if (started.get()) ScheduleMessageService.this.persist();
                    } catch (Throwable e) {
                        log.error("scheduleAtFixedRate flush exception", e);
                    }
                }
            }, 10000, this.defaultMessageStore.getMessageStoreConfig().getFlushDelayOffsetInterval());
        }
    }

    public void shutdown() {
        if (this.started.compareAndSet(true, false)) {
            if (null != this.timer)
                this.timer.cancel();
        }

    }

    public boolean isStarted() {
        return started.get();
    }

    public int getMaxDelayLevel() {
        return maxDelayLevel;
    }

    public String encode() {
        return this.encode(false);
    }

    /**
     * 主要完成延迟消息消费队列消息进度的加载(offsetTable)与delayLevelTable数据的构造
     * 延迟队列消息消费进度默认存储路径为{ROCKET_HOME}/store/config/delayOffset.json
     *
     * 同时解析MessageStoreConfig#messageDelayLevel定义的延迟级别转换为Map, 延迟级别1，2，3等对应的延迟时间
     * @return
     */
    public boolean load() {
        boolean result = super.load();
        result = result && this.parseDelayLevel();
        return result;
    }

    @Override
    public String configFilePath() {
        return StorePathConfigHelper.getDelayOffsetStorePath(this.defaultMessageStore.getMessageStoreConfig()
            .getStorePathRootDir());
    }

    @Override
    public void decode(String jsonString) {
        if (jsonString != null) {
            DelayOffsetSerializeWrapper delayOffsetSerializeWrapper =
                DelayOffsetSerializeWrapper.fromJson(jsonString, DelayOffsetSerializeWrapper.class);
            if (delayOffsetSerializeWrapper != null) {
                this.offsetTable.putAll(delayOffsetSerializeWrapper.getOffsetTable());
            }
        }
    }

    public String encode(final boolean prettyFormat) {
        DelayOffsetSerializeWrapper delayOffsetSerializeWrapper = new DelayOffsetSerializeWrapper();
        delayOffsetSerializeWrapper.setOffsetTable(this.offsetTable);
        return delayOffsetSerializeWrapper.toJson(prettyFormat);
    }

    public boolean parseDelayLevel() {
        HashMap<String, Long> timeUnitTable = new HashMap<String, Long>();
        timeUnitTable.put("s", 1000L);
        timeUnitTable.put("m", 1000L * 60);
        timeUnitTable.put("h", 1000L * 60 * 60);
        timeUnitTable.put("d", 1000L * 60 * 60 * 24);

        String levelString = this.defaultMessageStore.getMessageStoreConfig().getMessageDelayLevel();
        try {
            String[] levelArray = levelString.split(" ");
            for (int i = 0; i < levelArray.length; i++) {
                String value = levelArray[i];
                String ch = value.substring(value.length() - 1);
                Long tu = timeUnitTable.get(ch);

                int level = i + 1;
                if (level > this.maxDelayLevel) {
                    this.maxDelayLevel = level;
                }
                long num = Long.parseLong(value.substring(0, value.length() - 1));
                long delayTimeMillis = tu * num;
                this.delayLevelTable.put(level, delayTimeMillis);
            }
        } catch (Exception e) {
            log.error("parseDelayLevel exception", e);
            log.info("levelString String = {}", levelString);
            return false;
        }

        return true;
    }

    class DeliverDelayedMessageTimerTask extends TimerTask {
        private final int delayLevel;
        private final long offset;

        public DeliverDelayedMessageTimerTask(int delayLevel, long offset) {
            this.delayLevel = delayLevel;
            this.offset = offset;
        }

        @Override
        public void run() {
            try {
                if (isStarted()) {
                    this.executeOnTimeup();
                }
            } catch (Exception e) {
                // XXX: warn and notify me
                log.error("ScheduleMessageService, executeOnTimeup exception", e);
                ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                    this.delayLevel, this.offset), DELAY_FOR_A_PERIOD);
            }
        }

        /**
         * @return
         */
        private long correctDeliverTimestamp(final long now, final long deliverTimestamp) {

            long result = deliverTimestamp;

            long maxTimestamp = now + ScheduleMessageService.this.delayLevelTable.get(this.delayLevel);
            if (deliverTimestamp > maxTimestamp) {
                result = now;
            }

            return result;
        }

        /**
         * ScheduleMessageService的start方法启动后，会为每一个延迟级别创建一个调度任务
         * 每一个延迟级别其实对应SCHEDULE_TOPIC_XXXX主题下的一个消息消费队列
         * 定时调度任务的实现类为DeliverDelayedMessageTimerTask,其核心实现为executeOnTimeup
         *
         *
         */
        public void executeOnTimeup() {

            /**
             * 根据延迟主题与队列ID查找消息消费队列，如果未找到，说明目前并不存在该延时级别的消息，忽略本次任务
             * 根据延时级别创建下一次调度任务即可
             */
            ConsumeQueue cq =
                ScheduleMessageService.this.defaultMessageStore.findConsumeQueue(TopicValidator.RMQ_SYS_SCHEDULE_TOPIC,
                    delayLevel2QueueId(delayLevel));

            long failScheduleOffset = offset;

            if (cq != null) {

                /**
                 * 根据offset从消息消费队列中获取当前队列中所有有效的消息。
                 * 如果未找到，则更新一下延迟队列定时拉取进度，也就是failScheduleOffset，并创建定时任务待下一次继续尝试
                 */
                SelectMappedBufferResult bufferCQ = cq.getIndexBuffer(this.offset);
                if (bufferCQ != null) {
                    try {
                        long nextOffset = offset;
                        int i = 0;

                        /**
                         * 遍历ConsumeQueue，每一个标准ConsumeQueue条目为20个字节
                         * 解析出消息的物理偏移量，消息长度，消息tag hashCode，为从commitLog加载具体的消息做准备
                         */
                        ConsumeQueueExt.CqExtUnit cqExtUnit = new ConsumeQueueExt.CqExtUnit();
                        for (; i < bufferCQ.getSize(); i += ConsumeQueue.CQ_STORE_UNIT_SIZE) {
                            long offsetPy = bufferCQ.getByteBuffer().getLong();
                            int sizePy = bufferCQ.getByteBuffer().getInt();
                            long tagsCode = bufferCQ.getByteBuffer().getLong();

                            if (cq.isExtAddr(tagsCode)) {
                                if (cq.getExt(tagsCode, cqExtUnit)) {
                                    tagsCode = cqExtUnit.getTagsCode();
                                } else {
                                    //can't find ext content.So re compute tags code.
                                    log.error("[BUG] can't find consume queue extend file content!addr={}, offsetPy={}, sizePy={}",
                                        tagsCode, offsetPy, sizePy);
                                    long msgStoreTime = defaultMessageStore.getCommitLog().pickupStoreTimestamp(offsetPy, sizePy);
                                    tagsCode = computeDeliverTimestamp(delayLevel, msgStoreTime);
                                }
                            }

                            long now = System.currentTimeMillis();
                            long deliverTimestamp = this.correctDeliverTimestamp(now, tagsCode);

                            nextOffset = offset + (i / ConsumeQueue.CQ_STORE_UNIT_SIZE);

                            long countdown = deliverTimestamp - now;

                            // 如果时间到期
                            if (countdown <= 0) {
                                /**
                                 *  根据消息物理偏移量与消息大小从commitLog文件中查找消息，如果未找到消息，打印错误日志，根据延迟时间创建下一个定时器
                                 */
                                MessageExt msgExt =
                                    ScheduleMessageService.this.defaultMessageStore.lookMessageByOffset(
                                        offsetPy, sizePy);

                                if (msgExt != null) {
                                    try {

                                        /**
                                         * 根据消息重新构建新的消息对象，清除消息的延迟级别属性(delayLevel), 并恢复消息原先的消息主题与消息消费队列
                                         * 消息的消费次数reconsumeTimes并不会丢失的
                                         */
                                        MessageExtBrokerInner msgInner = this.messageTimeup(msgExt);
                                        if (TopicValidator.RMQ_SYS_TRANS_HALF_TOPIC.equals(msgInner.getTopic())) {
                                            log.error("[BUG] the real topic of schedule msg is {}, discard the msg. msg={}",
                                                    msgInner.getTopic(), msgInner);
                                            continue;
                                        }

                                        /**
                                         * 将消息再次存入到commitLog，并转发到主题对应的消息队列上，供消费者再次消费
                                         */
                                        PutMessageResult putMessageResult =
                                            ScheduleMessageService.this.writeMessageStore
                                                .putMessage(msgInner);

                                        if (putMessageResult != null
                                            && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) {

                                            if (ScheduleMessageService.this.defaultMessageStore.getMessageStoreConfig().isEnableScheduleMessageStats()) {
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incTopicPutNums(msgInner.getTopic(), putMessageResult.getAppendMessageResult().getMsgNum(), 1);
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incTopicPutSize(msgInner.getTopic(),
                                                    putMessageResult.getAppendMessageResult().getWroteBytes());
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incBrokerPutNums(putMessageResult.getAppendMessageResult().getMsgNum());
                                            }
                                            continue;
                                        } else {
                                            // XXX: warn and notify me
                                            log.error(
                                                "ScheduleMessageService, a message time up, but reput it failed, topic: {} msgId {}",
                                                msgExt.getTopic(), msgExt.getMsgId());
                                            ScheduleMessageService.this.timer.schedule(
                                                new DeliverDelayedMessageTimerTask(this.delayLevel,
                                                    nextOffset), DELAY_FOR_A_PERIOD);

                                            ScheduleMessageService.this.updateOffset(this.delayLevel,
                                                nextOffset);
                                            return;
                                        }
                                    } catch (Exception e) {
                                        /*
                                         * XXX: warn and notify me



                                         */
                                        log.error(
                                            "ScheduleMessageService, messageTimeup execute error, drop it. msgExt="
                                                + msgExt + ", nextOffset=" + nextOffset + ",offsetPy="
                                                + offsetPy + ",sizePy=" + sizePy, e);
                                    }
                                }
                            } else {
                                ScheduleMessageService.this.timer.schedule(
                                    new DeliverDelayedMessageTimerTask(this.delayLevel, nextOffset),
                                    countdown);
                                ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                                return;
                            }
                        } // end of for


                        /**
                         * 更新延迟队列拉取进度
                         */
                        nextOffset = offset + (i / ConsumeQueue.CQ_STORE_UNIT_SIZE);
                        ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                            this.delayLevel, nextOffset), DELAY_FOR_A_WHILE);
                        ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                        return;
                    } finally {

                        bufferCQ.release();
                    }
                } // end of if (bufferCQ != null)
                else {

                    long cqMinOffset = cq.getMinOffsetInQueue();
                    if (offset < cqMinOffset) {
                        failScheduleOffset = cqMinOffset;
                        log.error("schedule CQ offset invalid. offset=" + offset + ", cqMinOffset="
                            + cqMinOffset + ", queueId=" + cq.getQueueId());
                    }
                }
            } // end of if (cq != null)

            ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(this.delayLevel,
                failScheduleOffset), DELAY_FOR_A_WHILE);
        }

        private MessageExtBrokerInner messageTimeup(MessageExt msgExt) {
            MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
            msgInner.setBody(msgExt.getBody());
            msgInner.setFlag(msgExt.getFlag());
            MessageAccessor.setProperties(msgInner, msgExt.getProperties());

            TopicFilterType topicFilterType = MessageExt.parseTopicFilterType(msgInner.getSysFlag());
            long tagsCodeValue =
                MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
            msgInner.setTagsCode(tagsCodeValue);
            msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));

            msgInner.setSysFlag(msgExt.getSysFlag());
            msgInner.setBornTimestamp(msgExt.getBornTimestamp());
            msgInner.setBornHost(msgExt.getBornHost());
            msgInner.setStoreHost(msgExt.getStoreHost());
            msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());

            msgInner.setWaitStoreMsgOK(false);
            MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_DELAY_TIME_LEVEL);

            msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));

            String queueIdStr = msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID);
            int queueId = Integer.parseInt(queueIdStr);
            msgInner.setQueueId(queueId);

            return msgInner;
        }
    }
}
