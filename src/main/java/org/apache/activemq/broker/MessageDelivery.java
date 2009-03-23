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
package org.apache.activemq.broker;

import org.apache.activemq.broker.store.Store;
import org.apache.activemq.protobuf.AsciiBuffer;

public interface MessageDelivery {

    public Destination getDestination();

    public int getPriority();

    public int getFlowLimiterSize();

    public AsciiBuffer getMsgId();

    public AsciiBuffer getProducerId();

    public <T> T asType(Class<T> type);

    public boolean isPersistent();

    /**
     * Returns true if this message requires acknowledgement.
     */
    public boolean isResponseRequired();
    
    /**
     * Called when the message's persistence requirements have
     * been met. This method must not block. 
     */
    public void onMessagePersisted();
    
    public Store.Session.MessageRecord createMessageRecord();
}
