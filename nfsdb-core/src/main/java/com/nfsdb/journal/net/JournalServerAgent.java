/*
 * Copyright (c) 2014-2015. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.journal.net;

import com.nfsdb.journal.Journal;
import com.nfsdb.journal.JournalKey;
import com.nfsdb.journal.collections.DirectIntList;
import com.nfsdb.journal.exceptions.JournalDisconnectedChannelException;
import com.nfsdb.journal.exceptions.JournalException;
import com.nfsdb.journal.exceptions.JournalNetworkException;
import com.nfsdb.journal.logging.Logger;
import com.nfsdb.journal.net.auth.Authorizer;
import com.nfsdb.journal.net.bridge.JournalEvent;
import com.nfsdb.journal.net.bridge.JournalEventHandler;
import com.nfsdb.journal.net.bridge.JournalEventProcessor;
import com.nfsdb.journal.net.comsumer.JournalClientStateConsumer;
import com.nfsdb.journal.net.config.ServerConfig;
import com.nfsdb.journal.net.model.Command;
import com.nfsdb.journal.net.model.IndexedJournalKey;
import com.nfsdb.journal.net.model.JournalClientState;
import com.nfsdb.journal.net.producer.JournalDeltaProducer;
import com.nfsdb.journal.net.protocol.CommandConsumer;
import com.nfsdb.journal.net.protocol.CommandProducer;
import com.nfsdb.journal.net.protocol.Version;
import com.nfsdb.journal.net.protocol.commands.*;
import com.nfsdb.journal.utils.Lists;

import java.net.SocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

public class JournalServerAgent {

    private static final byte JOURNAL_INDEX_NOT_FOUND = -1;
    private final DirectIntList writerToReaderMap = new DirectIntList();
    private final DirectIntList readerToWriterMap = new DirectIntList();
    private final Logger LOGGER = Logger.getLogger(JournalServerAgent.class);
    private final JournalServer server;
    private final CommandConsumer commandConsumer = new CommandConsumer();
    private final CommandProducer commandProducer = new CommandProducer();
    private final SetKeyRequestConsumer setKeyRequestConsumer = new SetKeyRequestConsumer();
    private final StringResponseProducer stringResponseProducer = new StringResponseProducer();
    private final JournalClientStateConsumer journalClientStateConsumer = new JournalClientStateConsumer();
    private final IntResponseProducer intResponseProducer = new IntResponseProducer();
    private final IntResponseConsumer intResponseConsumer = new IntResponseConsumer();
    private final List<Journal> readers = new ArrayList<>();
    private final List<JournalDeltaProducer> producers = new ArrayList<>();
    private final List<JournalClientState> clientStates = new ArrayList<>();
    private final StatsCollectingWritableByteChannel statsChannel;
    private final JournalEventProcessor eventProcessor;
    private final EventHandler handler = new EventHandler();
    private final Authorizer authorizer;
    private final ByteArrayResponseConsumer byteArrayResponseConsumer = new ByteArrayResponseConsumer();
    private final SocketAddress socketAddress;
    private boolean authorized;

    public JournalServerAgent(JournalServer server, SocketAddress socketAddress, Authorizer authorizer) {
        this.server = server;
        this.socketAddress = socketAddress;
        this.statsChannel = new StatsCollectingWritableByteChannel(socketAddress);
        this.eventProcessor = new JournalEventProcessor(server.getBridge());
        this.authorizer = authorizer;
        this.authorized = authorizer == null;
        writerToReaderMap.zero(JOURNAL_INDEX_NOT_FOUND);
        readerToWriterMap.zero(JOURNAL_INDEX_NOT_FOUND);
    }

    public void close() {
        server.getBridge().removeAgentSequence(eventProcessor.getSequence());
        writerToReaderMap.free();
        readerToWriterMap.free();
    }

    public void process(ByteChannel channel) throws JournalNetworkException {
        commandConsumer.read(channel);
        if (commandConsumer.isComplete()) {
            switch (commandConsumer.getValue()) {
                case PROTOCOL_VERSION:
                    intResponseConsumer.read(channel);
                    if (intResponseConsumer.isComplete()) {
                        checkProtocolVersion(channel, intResponseConsumer.getValue());
                        intResponseConsumer.reset();
                        commandConsumer.reset();
                    }
                    break;
                case HANDSHAKE_COMPLETE:
                    if (authorized) {
                        ok(channel);
                    } else {
                        stringResponseProducer.write(channel, "AUTH");
                    }
                    commandConsumer.reset();
                    break;
                case AUTHORIZATION:
                    byteArrayResponseConsumer.read(channel);
                    if (byteArrayResponseConsumer.isComplete()) {
                        authorize(channel, byteArrayResponseConsumer.getValue());
                    }
                    byteArrayResponseConsumer.reset();
                    commandConsumer.reset();
                    break;
                case SET_KEY_CMD:
                    LOGGER.trace("SetKey command received");
                    setKeyRequestConsumer.read(channel);
                    if (setKeyRequestConsumer.isComplete()) {
                        setClientKey(channel, setKeyRequestConsumer.getValue());
                        setKeyRequestConsumer.reset();
                        commandConsumer.reset();
                    }
                    break;
                case DELTA_REQUEST_CMD:
                    checkAuthorized(channel);
                    LOGGER.trace("DeltaRequest command received");
                    journalClientStateConsumer.read(channel);
                    if (journalClientStateConsumer.isComplete()) {
                        storeDeltaRequest(channel, journalClientStateConsumer.getValue());
                        journalClientStateConsumer.reset();
                        commandConsumer.reset();
                    }
                    break;
                case CLIENT_READY_CMD:
                    checkAuthorized(channel);
                    statsChannel.setDelegate(channel);
                    dispatchData2(statsChannel);
                    statsChannel.logStats();
                    commandConsumer.reset();
                    break;
                case CLIENT_DISCONNECT:
                    throw new JournalDisconnectedChannelException();
                default:
                    throw new JournalNetworkException("Corrupt channel");
            }
        }
    }

    private void authorize(WritableByteChannel channel, byte[] value) throws JournalNetworkException {
        if (!authorized) {
            try {
                ArrayList<JournalKey> keys = new ArrayList<>(readers.size());
                for (int i = 0, sz = readers.size(); i < sz; i++) {
                    keys.add(readers.get(i).getKey());
                }
                authorized = authorizer.isAuthorized(value, keys);
            } catch (Throwable e) {
                LOGGER.error("Exception in authorizer: ", e);
                authorized = false;
            }
        }

        if (authorized) {
            ok(channel);
        } else {
            error(channel, "Authorization failed");
        }
    }

    private void checkAuthorized(WritableByteChannel channel) throws JournalNetworkException {
        if (!authorized) {
            error(channel, "NOT AUTHORIZED");
            throw new JournalDisconnectedChannelException();
        }
    }


    private void checkProtocolVersion(ByteChannel channel, int version) throws JournalNetworkException {
        if (version == Version.PROTOCOL_VERSION) {
            ok(channel);
        } else {
            error(channel, "Unsupported protocol version. Client: " + version + ", Server: " + Version.PROTOCOL_VERSION);
        }

    }

    private <T> void createReader(int index, JournalKey<T> key) throws JournalException {

        Lists.advance(readers, index);

        Journal<?> journal = readers.get(index);
        if (journal == null) {
            journal = server.getFactory().reader(key);
            readers.set(index, journal);
        }

        Lists.advance(producers, index);

        JournalDeltaProducer producer = producers.get(index);
        if (producer == null) {
            producer = new JournalDeltaProducer(journal);
            producers.set(index, producer);
        }
    }

    private void setClientKey(ByteChannel channel, IndexedJournalKey indexedKey) throws JournalNetworkException {
        JournalKey<?> readerKey = indexedKey.getKey();
        int maxIndex = server.getMaxWriterIndex();
        if (indexedKey.getIndex() > maxIndex) {
            error(channel, "Journal index is too large. Max " + maxIndex);
        } else {
            int writerIndex = server.getWriterIndex(readerKey);
            if (writerIndex == JournalServer.JOURNAL_KEY_NOT_FOUND) {
                error(channel, "Requested key not exported: " + readerKey);
            } else {
                writerToReaderMap.extendAndSet(writerIndex, indexedKey.getIndex());
                readerToWriterMap.extendAndSet(indexedKey.getIndex(), writerIndex);
                try {
                    createReader(indexedKey.getIndex(), readerKey);
                    ok(channel);
                } catch (JournalException e) {
                    error(channel, "Could not created reader for key: " + readerKey, e);
                }
            }
        }
    }

    private void storeDeltaRequest(WritableByteChannel channel, JournalClientState request) throws JournalNetworkException {
        int index = request.getJournalIndex();

        if (readerToWriterMap.get(index) == JOURNAL_INDEX_NOT_FOUND) {
            error(channel, "Journal index does not match key request");
        } else {
            Lists.advance(clientStates, index);

            JournalClientState r = clientStates.get(index);
            if (r == null) {
                r = new JournalClientState();
                clientStates.set(index, r);
            }
            request.deepCopy(r);
            r.invalidateClientState();
            r.setClientStateSyncTime(0);
            r.setWriterUpdateReceived(false);

            ok(channel);
        }
    }

    private void ok(WritableByteChannel channel) throws JournalNetworkException {
        stringResponseProducer.write(channel, "OK");
    }

    private void error(WritableByteChannel channel, String message) throws JournalNetworkException {
        error(channel, message, null);
    }

    private void error(WritableByteChannel channel, String message, Exception e) throws JournalNetworkException {
        stringResponseProducer.write(channel, message);
        LOGGER.info(message, e);
    }

    private boolean processJournalEvents(final WritableByteChannel channel, boolean blocking) throws JournalNetworkException {

        handler.setChannel(channel);
        boolean dataSent = false;
        if (eventProcessor.process(handler, blocking)) {
            dataSent = handler.isDataSent();

            // handler would have dispatched those journals, which received updates
            // this loop does two things:
            // 1. attempts to dispatch journals that didn't receive updates, dispatch method would check timeout and decide.
            // 2. reset writer update received status
            for (int i = 0, sz = clientStates.size(); i < sz; i++) {
                JournalClientState state = clientStates.get(i);
                if (state.noCommitNotification()) {
                    dataSent = dispatch(channel, i) || dataSent;
                }
                state.setWriterUpdateReceived(false);
            }

            if (dataSent) {
                commandProducer.write(channel, Command.SERVER_READY_CMD);
            } else if (blocking) {
                LOGGER.error("Client appears to be refusing new data from server, corrupt client? [" + socketAddress + "]");
            }
        } else {
            commandProducer.write(channel, Command.SERVER_HEARTBEAT);
            LOGGER.debug("Heartbeat: %s", channel);
        }
        return dataSent;
    }

    private boolean dispatch(WritableByteChannel channel, int journalIndex) {
        long time = System.currentTimeMillis();
        JournalClientState state = clientStates.get(journalIndex);
        JournalDeltaProducer journalDeltaProducer = getProducer(journalIndex);

        // x1 is clientStateValid
        // x2 is writerUpdateReceived
        // x3 is clientStateSynchronised
        // 1 is true
        // 0 is false
        //
        // x1 = 1 && x3 = 0 -> send (brand new, unvalidated request)
        // x1 = 0 - don't send (not client state, don't know what to send)
        // x1 = 1 && x2 = 1 ->  send (either new request, or previously validated but not sent with and update)

        if (journalDeltaProducer == null || state == null || state.isClientStateInvalid() ||
                (state.noCommitNotification() && time - state.getClientStateSyncTime() <= ServerConfig.SYNC_TIMEOUT)) {
            return false;
        }


        try {
            boolean dataSent = dispatchProducer(channel, state, journalDeltaProducer, journalIndex);
            if (dataSent) {
                state.invalidateClientState();
            } else {
                state.setClientStateSyncTime(time);
            }
            return dataSent;
        } catch (Exception e) {
            LOGGER.error("Cannot produce delta", e);
            return false;
        }
    }

    private void dispatchData2(WritableByteChannel channel) throws JournalNetworkException {
        if (!processJournalEvents(channel, false)) {
            processJournalEvents(channel, true);
        }
    }

    private JournalDeltaProducer getProducer(int index) {
        if (index < producers.size()) {
            return producers.get(index);
        } else {
            return null;
        }
    }

    private boolean dispatchProducer(WritableByteChannel channel,
                                     JournalClientState state, JournalDeltaProducer journalDeltaProducer,
                                     int index) throws JournalNetworkException, JournalException {
        journalDeltaProducer.configure(state);
        if (journalDeltaProducer.hasContent()) {
            commandProducer.write(channel, Command.JOURNAL_DELTA_CMD);
            intResponseProducer.write(channel, index);
            journalDeltaProducer.write(channel);
            return true;
        }
        return false;

    }

    private class EventHandler implements JournalEventHandler {

        private WritableByteChannel channel;
        private boolean dataSent = false;

        public void setChannel(WritableByteChannel channel) {
            this.channel = channel;
            this.dataSent = false;
        }

        public boolean isDataSent() {
            return dataSent;
        }

        @Override
        public void handle(JournalEvent event) {
            int journalIndex = writerToReaderMap.get(event.getIndex());
            if (journalIndex != JOURNAL_INDEX_NOT_FOUND) {
                JournalClientState status = clientStates.get(journalIndex);
                if (status != null && status.noCommitNotification()) {
                    status.setWriterUpdateReceived(true);
                    dataSent = dispatch(channel, journalIndex) || dataSent;
                }
            }
        }
    }
}
