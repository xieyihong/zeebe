/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.dispatcher.FragmentHandler;
import io.zeebe.transport.impl.ClientOutputImpl;
import io.zeebe.transport.impl.ClientReceiveHandler;
import io.zeebe.transport.impl.ClientRequestPool;
import io.zeebe.transport.impl.ClientSendFailureHandler;
import io.zeebe.transport.impl.RemoteAddressList;
import io.zeebe.transport.impl.TransportChannelFactory;
import io.zeebe.transport.impl.TransportContext;
import io.zeebe.transport.impl.actor.ClientActorContext;
import io.zeebe.transport.impl.actor.ClientConductor;
import io.zeebe.transport.impl.actor.Receiver;
import io.zeebe.transport.impl.actor.Sender;
import io.zeebe.util.actor.ActorScheduler;

public class ClientTransportBuilder
{

    public static final String SEND_BUFFER_SUBSCRIPTION_NAME = ServerTransportBuilder.SEND_BUFFER_SUBSCRIPTION_NAME;

    /**
     * In the same order of magnitude of what apache and nginx use.
     */
    protected static final long DEFAULT_CHANNEL_KEEP_ALIVE_PERIOD = 5000;
    protected static final long DEFAULT_CHANNEL_CONNECT_TIMEOUT = 500;

    private int requestPoolSize = 64;
    private int messageMaxLength = 1024 * 512;
    protected long keepAlivePeriod = DEFAULT_CHANNEL_KEEP_ALIVE_PERIOD;
    protected long channelConnectTimeout = DEFAULT_CHANNEL_CONNECT_TIMEOUT;

    protected Dispatcher receiveBuffer;
    private Dispatcher sendBuffer;
    private ActorScheduler scheduler;
    protected List<ClientInputListener> listeners;
    protected TransportChannelFactory channelFactory;

    public ClientTransportBuilder scheduler(ActorScheduler scheduler)
    {
        this.scheduler = scheduler;
        return this;
    }

    /**
     * Optional. If set, all incoming messages (single-message protocol) are put onto the provided buffer.
     * {@link ClientTransport#openSubscription(String, ClientMessageHandler)} can be used to consume from this buffer.
     */
    public ClientTransportBuilder messageReceiveBuffer(Dispatcher receiveBuffer)
    {
        this.receiveBuffer = receiveBuffer;
        return this;
    }

    public ClientTransportBuilder inputListener(ClientInputListener listener)
    {
        if (listeners == null)
        {
            listeners = new ArrayList<>();
        }
        this.listeners.add(listener);
        return this;
    }

    /**
     * Send buffer must have an open subscription named {@link ClientTransportBuilder#SEND_BUFFER_SUBSCRIPTION_NAME}.
     */
    public ClientTransportBuilder sendBuffer(Dispatcher sendBuffer)
    {
        this.sendBuffer = sendBuffer;
        return this;
    }

    public ClientTransportBuilder messageMaxLength(int messageMaxLength)
    {
        this.messageMaxLength = messageMaxLength;
        return this;
    }

    /**
     * Maximum number of concurrent requests.
     */
    public ClientTransportBuilder requestPoolSize(int requestPoolSize)
    {
        this.requestPoolSize = requestPoolSize;
        return this;
    }

    /**
     * The period in which a dummy message is sent to keep the underlying TCP connection open.
     */
    public ClientTransportBuilder keepAlivePeriod(long keepAlivePeriod)
    {
        this.keepAlivePeriod = keepAlivePeriod;
        return this;
    }

    /**
     * The maximum wait time until a request is discarded if not connection can be established to
     * the remote.
     */
    public ClientTransportBuilder channelConnectTimeout(long channelConnectTimeout)
    {
        this.channelConnectTimeout = channelConnectTimeout;
        return this;
    }

    public ClientTransportBuilder channelFactory(TransportChannelFactory channelFactory)
    {
        this.channelFactory = channelFactory;
        return this;
    }

    public ClientTransport build()
    {
        validate();
        final ClientRequestPool clientRequestPool = new ClientRequestPool(requestPoolSize, sendBuffer);
        final ClientOutput output = new ClientOutputImpl(sendBuffer, clientRequestPool);
        final RemoteAddressList remoteAddressList = new RemoteAddressList();

        final TransportContext transportContext =
                buildTransportContext(
                        output,
                        clientRequestPool,
                        remoteAddressList,
                        new ClientReceiveHandler(clientRequestPool, receiveBuffer, listeners),
                        receiveBuffer);

        return build(transportContext);
    }

    protected TransportContext buildTransportContext(
            ClientOutput output,
            ClientRequestPool clientRequestPool,
            RemoteAddressList addressList,
            FragmentHandler receiveHandler,
            Dispatcher receiveBuffer)
    {
        final TransportContext context = new TransportContext();
        context.setClientOutput(output);
        context.setReceiveBuffer(receiveBuffer);
        context.setMessageMaxLength(messageMaxLength);
        context.setClientRequestPool(clientRequestPool);
        context.setRemoteAddressList(addressList);
        context.setReceiveHandler(receiveHandler);
        context.setSenderSubscription(sendBuffer.getSubscriptionByName(SEND_BUFFER_SUBSCRIPTION_NAME));
        context.setSendFailureHandler(new ClientSendFailureHandler(clientRequestPool));
        context.setChannelKeepAlivePeriod(keepAlivePeriod);
        context.setChannelConnectTimeout(channelConnectTimeout);

        if (channelFactory != null)
        {
            context.setChannelFactory(channelFactory);
        }

        return context;
    }

    protected ClientTransport build(TransportContext context)
    {
        final ClientActorContext actorContext = new ClientActorContext();

        final ClientConductor conductor = new ClientConductor(actorContext, context);
        final Sender sender = new Sender(actorContext, context);
        final Receiver receiver = new Receiver(actorContext, context);

        context.setActorReferences(
            scheduler.schedule(conductor),
            scheduler.schedule(sender),
            scheduler.schedule(receiver));

        return new ClientTransport(actorContext, context);
    }

    private void validate()
    {
        Objects.requireNonNull(scheduler, "Scheduler must be provided");
        Objects.requireNonNull(sendBuffer, "Send buffer must be provieded");
    }

}
