/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.apollo.amqp.hawtdispatch.api;

import org.apache.qpid.proton.engine.Endpoint;
import org.apache.qpid.proton.engine.Link;
import org.apache.qpid.proton.engine.impl.ReceiverImpl;
import org.apache.qpid.proton.engine.impl.SenderImpl;
import org.apache.qpid.proton.engine.impl.SessionImpl;
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.type.Binary;
import org.apache.qpid.proton.type.messaging.*;
import org.apache.qpid.proton.type.transport.SenderSettleMode;

import java.util.UUID;

/**
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class AmqpSession extends AmqpEndpointBase {

    final AmqpConnection parent;
    final SessionImpl session;


    public AmqpSession(AmqpConnection parent, SessionImpl session) {
        this.parent = parent;
        this.session = session;
        attach();
    }

    @Override
    protected Endpoint getEndpoint() {
        return session;
    }

    @Override
    protected AmqpConnection getParent() {
        return parent;
    }

    public AmqpSender createSender(Target target) {
        return createSender(target, QoS.AT_LEAST_ONCE);
    }

    public AmqpSender createSender(Target target, QoS qos) {
        return createSender(target, qos, UUID.randomUUID().toString());
    }

    public AmqpSender createSender(Target target, QoS qos, String name) {
        assertExecuting();
        SenderImpl sender = session.sender(name);
        attach();
//        Source source = new Source();
//        source.setAddress(UUID.randomUUID().toString());
//        sender.setSource(source);
        sender.setTarget(target);
        configureQos(sender, qos);
        sender.open();
        pumpOut();
        return new AmqpSender(this, sender, qos);
    }

    public AmqpReceiver createReceiver(Source source) {
        return createReceiver(source, QoS.AT_LEAST_ONCE);
    }

    public AmqpReceiver createReceiver(Source source, QoS qos) {
        return createReceiver(source, qos, 100);
    }

    public AmqpReceiver createReceiver(Source source, QoS qos, int prefetch) {
        return createReceiver(source, qos, prefetch,  UUID.randomUUID().toString());
    }

    public AmqpReceiver createReceiver(Source source, QoS qos, int prefetch, String name) {
        assertExecuting();
        ReceiverImpl receiver = session.receiver(name);
        receiver.setSource(source);
//        Target target = new Target();
//        target.setAddress(UUID.randomUUID().toString());
//        receiver.setTarget(target);
        receiver.flow(prefetch);
        configureQos(receiver, qos);
        receiver.open();
        pumpOut();
        return new AmqpReceiver(this, receiver, qos);
    }

    private void configureQos(Link link, QoS qos) {
        switch (qos) {
            case AT_MOST_ONCE:
                link.setSenderSettleMode(SenderSettleMode.SETTLED);
                link.setReceiverSettleMode(SenderSettleMode.UNSETTLED);
                break;
            case AT_LEAST_ONCE:
                link.setSenderSettleMode(SenderSettleMode.UNSETTLED);
                link.setReceiverSettleMode(SenderSettleMode.SETTLED);
                break;
            case EXACTLY_ONCE:
                link.setSenderSettleMode(SenderSettleMode.UNSETTLED);
                link.setReceiverSettleMode(SenderSettleMode.MIXED);
                break;
        }
    }

    public Message createTextMessage(String value) {
        Message msg = new Message();
        Section body = new AmqpValue(value);
        msg.setBody(body);
        return msg;
    }

    public Message createBinaryMessage(byte value[]) {
        return createBinaryMessage(value, 0, value.length);
    }

    public Message createBinaryMessage(byte value[], int offset, int len) {
        Message msg = new Message();
        Data body = new Data(new Binary(value, offset,len));
        msg.setBody(body);
        return msg;
    }
}
