/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.message;

import org.apache.qpid.AMQException;
import org.apache.qpid.server.consumer.Consumer;
import org.apache.qpid.server.consumer.ConsumerTarget;
import org.apache.qpid.server.filter.FilterManager;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.security.AuthorizationHolder;
import org.apache.qpid.server.store.TransactionLogResource;

import java.util.Collection;
import java.util.EnumSet;

public interface MessageSource<C extends Consumer, S extends MessageSource<C,S>> extends TransactionLogResource, MessageNode
{
    <T extends ConsumerTarget> C addConsumer(T target, FilterManager filters,
                         Class<? extends ServerMessage> messageClass,
                         String consumerName, EnumSet<Consumer.Option> options) throws AMQException;

    Collection<C> getConsumers();

    void addConsumerRegistrationListener(ConsumerRegistrationListener<S> listener);

    void removeConsumerRegistrationListener(ConsumerRegistrationListener<S> listener);

    AuthorizationHolder getAuthorizationHolder();

    void setAuthorizationHolder(AuthorizationHolder principalHolder);

    void setExclusiveOwningSession(AMQSessionModel owner);

    AMQSessionModel getExclusiveOwningSession();

    boolean isExclusive();

    interface ConsumerRegistrationListener<Q extends MessageSource<? extends Consumer,Q>>
    {
        void consumerAdded(Q source, Consumer consumer);
        void consumerRemoved(Q queue, Consumer consumer);
    }

    /**
     * ExistingExclusiveConsumer signals a failure to create a consumer, because an exclusive consumer
     * already exists.
     *
     * <p/><table id="crc"><caption>CRC Card</caption>
     * <tr><th> Responsibilities <th> Collaborations
     * <tr><td> Represent failure to create a consumer, because an exclusive consumer already exists.
     * </table>
     *
     * @todo Not an AMQP exception as no status code.
     *
     * @todo Move to top level, used outside this class.
     */
    static final class ExistingExclusiveConsumer extends AMQException
    {

        public ExistingExclusiveConsumer()
        {
            super("");
        }
    }

    /**
     * ExistingConsumerPreventsExclusive signals a failure to create an exclusive consumer, as a consumer
     * already exists.
     *
     * <p/><table id="crc"><caption>CRC Card</caption>
     * <tr><th> Responsibilities <th> Collaborations
     * <tr><td> Represent failure to create an exclusive consumer, as a consumer already exists.
     * </table>
     *
     * @todo Not an AMQP exception as no status code.
     *
     * @todo Move to top level, used outside this class.
     */
    static final class ExistingConsumerPreventsExclusive extends AMQException
    {
        public ExistingConsumerPreventsExclusive()
        {
            super("");
        }
    }
}
