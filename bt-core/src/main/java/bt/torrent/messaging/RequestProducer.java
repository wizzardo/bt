/*
 * Copyright (c) 2016—2017 Andrei Tomashpolskiy and individual contributors.
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

package bt.torrent.messaging;

import bt.BtException;
import bt.data.ChunkDescriptor;
import bt.data.DataDescriptor;
import bt.net.Peer;
import bt.protocol.Cancel;
import bt.protocol.InvalidMessageException;
import bt.protocol.Message;
import bt.protocol.Request;
import bt.data.Bitfield;
import bt.torrent.annotation.Produces;
import bt.torrent.data.BlockWrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Produces block requests to the remote peer.
 *
 * @since 1.0
 */
public class RequestProducer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestProducer.class);

    private Bitfield bitfield;
    private List<ChunkDescriptor> chunks;
    private final int maxOutstandingRequests;

    public RequestProducer(DataDescriptor dataDescriptor, int maxOutstandingRequests) {
        this.bitfield = dataDescriptor.getBitfield();
        this.chunks = dataDescriptor.getChunkDescriptors();
        this.maxOutstandingRequests = maxOutstandingRequests;
    }

    @Produces
    public void produce(Consumer<Message> messageConsumer, MessageContext context) {

        Peer peer = context.getPeer();
        ConnectionState connectionState = context.getConnectionState();

        if (!connectionState.getCurrentAssignment().isPresent()) {
            resetConnection(connectionState, messageConsumer);
            return;
        }

        Assignment assignment = connectionState.getCurrentAssignment().get();
        Queue<Integer> assignedPieces = assignment.getPieces();
        if (assignedPieces.isEmpty()) {
            resetConnection(connectionState, messageConsumer);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("All pieces assigned to peer {} have been finished." +
                        " Will wait for the next assignment.", peer);
            }
            return;
        } else {
            List<Integer> finishedPieces = null;
            for (Integer assignedPiece : assignedPieces) {
                if (bitfield.isComplete(assignedPiece)) {
                    if (finishedPieces == null) {
                        finishedPieces = new ArrayList<>(assignedPieces.size() + 1);
                    }
                    // delay removing piece from assignments to avoid CME
                    finishedPieces.add(assignedPiece);
                } else if (connectionState.getEnqueuedPieces().add(assignedPiece)) {
                    addRequestsToQueue(connectionState, assignedPiece);
                }
            }
            if (finishedPieces != null) {
                finishedPieces.forEach(finishedPiece -> {
                    assignment.finish(finishedPiece);
                    connectionState.getEnqueuedPieces().remove(finishedPiece);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Finished downloading piece #{}", finishedPiece);
                    }
                });
            }
        }

        Queue<Request> requestQueue = connectionState.getRequestQueue();
        while (!requestQueue.isEmpty() && connectionState.getPendingRequests().size() <= maxOutstandingRequests) {
            Request request = requestQueue.poll();
            Object key = Mapper.mapper().buildKey(request.getPieceIndex(), request.getOffset(), request.getLength());
            messageConsumer.accept(request);
            connectionState.getPendingRequests().add(key);
        }
    }

    private void resetConnection(ConnectionState connectionState, Consumer<Message> messageConsumer) {
        connectionState.getRequestQueue().clear();
        connectionState.getEnqueuedPieces().clear();
        if (!connectionState.getPendingRequests().isEmpty())
            connectionState.getPendingRequests().forEach(r -> {
                Mapper.decodeKey(r).ifPresent(key -> {
                    messageConsumer.accept(new Cancel(key.getPieceIndex(), key.getOffset(), key.getLength()));
                });
            });
        connectionState.getPendingRequests().clear();
        connectionState.getPendingWrites().clear();
    }

    private void addRequestsToQueue(ConnectionState connectionState, Integer pieceIndex) {
        List<Request> requests = buildRequests(pieceIndex).stream()
                .filter(request -> {
                    Object key = Mapper.mapper().buildKey(
                            request.getPieceIndex(), request.getOffset(), request.getLength());
                    if (connectionState.getPendingRequests().contains(key)) {
                        return false;
                    }

                    CompletableFuture<BlockWrite> future = connectionState.getPendingWrites().get(key);
                    if (future == null) {
                        return true;
                    } else if (!future.isDone()) {
                        return false;
                    }

                    BlockWrite block = future.getNow(null);
                    boolean failed = block.getError().isPresent();
                    if (failed) {
                        connectionState.getPendingWrites().remove(key);
                    }
                    return failed;
                }).collect(Collectors.toList());

        Collections.shuffle(requests);

        connectionState.getRequestQueue().addAll(requests);
    }

    private List<Request> buildRequests(int pieceIndex) {
        List<Request> requests = new ArrayList<>();
        ChunkDescriptor chunk = chunks.get(pieceIndex);
        long chunkSize = chunk.getData().length();
        long blockSize = chunk.blockSize();

        for (int blockIndex = 0; blockIndex < chunk.blockCount(); blockIndex++) {
            if (!chunk.isPresent(blockIndex)) {
                int offset = (int) (blockIndex * blockSize);
                int length = (int) Math.min(blockSize, chunkSize - offset);
                try {
                    requests.add(new Request(pieceIndex, offset, length));
                } catch (InvalidMessageException e) {
                    // shouldn't happen
                    throw new BtException("Unexpected error", e);
                }
            }
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Built {} requests for piece #{} (size: {}, block size: {}, number of blocks: {})",
                    requests.size(), pieceIndex, chunkSize, blockSize, chunk.blockCount());
        }
        return requests;
    }
}
