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
package org.apache.rocketmq.broker.transaction;

import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;

/**
 * RocketMQ基于两阶段协议发送与提交回滚消息
 *
 * 事务消息存储在消息服务器时，主题被替换为RMQ_SYS_TRANS_HALF_TOPIC, 执行完本地事务返回状态为UN_KNOW,结束时将不做任务处理
 * 而是通过事务状态定时回查得到发送端明确的事务操作(提交事务或者回滚事务)
 */

/**
 * RocketMQ通过TransactionalMessageCheckService线程定时去检测RMQ_SYS_TRANS_HALF_TOPIC主题中的消息
 * 回查消息的事务状态，TransactionalMessageCheckService的检测频率默认为1分钟，可以通过在broker.conf文件设置transactionCheckInterval来改变默认值，单位为毫秒
 */
public class TransactionalMessageCheckService extends ServiceThread {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.TRANSACTION_LOGGER_NAME);

    private BrokerController brokerController;

    public TransactionalMessageCheckService(BrokerController brokerController) {
        this.brokerController = brokerController;
    }

    @Override
    public String getServiceName() {
        return TransactionalMessageCheckService.class.getSimpleName();
    }

    @Override
    public void run() {
        log.info("Start transaction check service thread!");
        long checkInterval = brokerController.getBrokerConfig().getTransactionCheckInterval();
        while (!this.isStopped()) {
            this.waitForRunning(checkInterval);
        }
        log.info("End transaction check service thread!");
    }

    @Override
    protected void onWaitEnd() {
        /**
         * transactionTimeOut: 事务的过期时间，只有当消息的存储时间加上过期时间大于系统当前时间时，才对消息执行事务状态回查，否则在下一次周期中执行事务回查操作
         *
         * transactionCheckMax: 事务回查最大检测次数，如果超过最大检测次数还是无法获知消息的事务状态，RocketMQ将不会继续对消息进行事务状态回查，而是直接丢弃即相当于回滚事务
         */
        long timeout = brokerController.getBrokerConfig().getTransactionTimeOut();
        int checkMax = brokerController.getBrokerConfig().getTransactionCheckMax();
        long begin = System.currentTimeMillis();
        log.info("Begin to check prepare message, begin time:{}", begin);
        this.brokerController.getTransactionalMessageService().check(timeout, checkMax, this.brokerController.getTransactionalMessageCheckListener());
        log.info("End to check prepare message, consumed time:{}", System.currentTimeMillis() - begin);
    }

}
