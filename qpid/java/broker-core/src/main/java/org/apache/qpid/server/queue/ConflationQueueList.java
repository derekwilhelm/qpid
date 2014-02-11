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

package org.apache.qpid.server.queue;

import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.txn.AutoCommitTransaction;
import org.apache.qpid.server.txn.ServerTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class ConflationQueueList extends OrderedQueueEntryList<ConflationQueueList.ConflationQueueEntry, ConflationQueue, ConflationQueueList>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConflationQueueList.class);

    private static final HeadCreator<ConflationQueueList.ConflationQueueEntry, ConflationQueue, ConflationQueueList> HEAD_CREATOR = new HeadCreator<ConflationQueueList.ConflationQueueEntry, ConflationQueue, ConflationQueueList>()
    {

        @Override
        public ConflationQueueEntry createHead(final ConflationQueueList list)
        {
            return list.createHead();
        }
    };

    private final String _conflationKey;
    private final ConcurrentHashMap<Object, AtomicReference<ConflationQueueEntry>> _latestValuesMap =
        new ConcurrentHashMap<Object, AtomicReference<ConflationQueueEntry>>();

    private final ConflationQueueEntry _deleteInProgress = new ConflationQueueEntry(this);
    private final ConflationQueueEntry _newerEntryAlreadyBeenAndGone = new ConflationQueueEntry(this);

    public ConflationQueueList(ConflationQueue queue, String conflationKey)
    {
        super(queue, HEAD_CREATOR);
        _conflationKey = conflationKey;
    }

    private ConflationQueueEntry createHead()
    {
        return new ConflationQueueEntry(this);
    }

    public String getConflationKey()
    {
        return _conflationKey;
    }

    @Override
    protected ConflationQueueEntry createQueueEntry(ServerMessage message)
    {
        return new ConflationQueueEntry(this, message);
    }

    /**
     * Updates the list using super.add and also updates {@link #_latestValuesMap} and discards entries as necessary.
     */
    @Override
    public ConflationQueueEntry add(final ServerMessage message)
    {
        final ConflationQueueEntry addedEntry = super.add(message);

        final Object keyValue = message.getMessageHeader().getHeader(_conflationKey);
        if (keyValue != null)
        {
            if(LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Adding entry " + addedEntry + " for message " + message.getMessageNumber() + " with conflation key " + keyValue);
            }

            final AtomicReference<ConflationQueueEntry> referenceToEntry = new AtomicReference<ConflationQueueEntry>(addedEntry);
            AtomicReference<ConflationQueueEntry> entryReferenceFromMap;
            ConflationQueueEntry entryFromMap;

            // Iterate until we have got a valid atomic reference object and either the referent is newer than the current
            // entry, or the current entry has replaced it in the reference. Note that the _deletedEntryPlaceholder is a special value
            // indicating that the reference object is no longer valid (it is being removed from the map).
            boolean keepTryingToUpdateEntryReference;
            do
            {
                do
                {
                    entryReferenceFromMap = getOrPutIfAbsent(keyValue, referenceToEntry);

                    // entryFromMap can be either an older entry, a newer entry (added recently by another thread), or addedEntry (if it's for a new key value)  
                    entryFromMap = entryReferenceFromMap.get();
                }
                while(entryFromMap == _deleteInProgress);

                boolean entryFromMapIsOlder = entryFromMap != _newerEntryAlreadyBeenAndGone && entryFromMap.compareTo(addedEntry) < 0;

                keepTryingToUpdateEntryReference = entryFromMapIsOlder
                        && !entryReferenceFromMap.compareAndSet(entryFromMap, addedEntry);
            }
            while(keepTryingToUpdateEntryReference);

            if (entryFromMap == _newerEntryAlreadyBeenAndGone)
            {
                discardEntry(addedEntry);
            }
            else if (entryFromMap.compareTo(addedEntry) > 0)
            {
                if(LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("New entry " + addedEntry.getEntryId() + " for message " + addedEntry.getMessage().getMessageNumber() + " being immediately discarded because a newer entry arrived. The newer entry is: " + entryFromMap + " for message " + entryFromMap.getMessage().getMessageNumber());
                }
                discardEntry(addedEntry);
            }
            else if (entryFromMap.compareTo(addedEntry) < 0)
            {
                if(LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("Entry " + addedEntry + " for message " + addedEntry.getMessage().getMessageNumber() + " replacing older entry " + entryFromMap + " for message " + entryFromMap.getMessage().getMessageNumber());
                }
                discardEntry(entryFromMap);
            }

            addedEntry.setLatestValueReference(entryReferenceFromMap);
        }

        return addedEntry;
    }

    /**
     * Returns:
     *
     * <ul>
     * <li>the existing entry reference if the value already exists in the map, or</li>
     * <li>referenceToValue if none exists, or</li>
     * <li>a reference to {@link #_newerEntryAlreadyBeenAndGone} if another thread concurrently
     * adds and removes during execution of this method.</li>
     * </ul>
     */
    private AtomicReference<ConflationQueueEntry> getOrPutIfAbsent(final Object key, final AtomicReference<ConflationQueueEntry> referenceToAddedValue)
    {
        AtomicReference<ConflationQueueEntry> latestValueReference = _latestValuesMap.putIfAbsent(key, referenceToAddedValue);

        if(latestValueReference == null)
        {
            latestValueReference = _latestValuesMap.get(key);
            if(latestValueReference == null)
            {
                return new AtomicReference<ConflationQueueEntry>(_newerEntryAlreadyBeenAndGone);
            }
        }
        return latestValueReference;
    }

    private void discardEntry(final QueueEntry entry)
    {
        if(entry.acquire())
        {
            ServerTransaction txn = new AutoCommitTransaction(getQueue().getVirtualHost().getMessageStore());
            txn.dequeue(entry.getQueue(),entry.getMessage(),
                                    new ServerTransaction.Action()
                                {
                                    @Override
                                    public void postCommit()
                                    {
                                        entry.delete();
                                    }

                                    @Override
                                    public void onRollback()
                                    {

                                    }
                                });
        }
    }

    final class ConflationQueueEntry extends OrderedQueueEntry<ConflationQueueEntry, ConflationQueue, ConflationQueueList>
    {

        private AtomicReference<ConflationQueueEntry> _latestValueReference;

        private ConflationQueueEntry(final ConflationQueueList queueEntryList)
        {
            super(queueEntryList);
        }

        public ConflationQueueEntry(ConflationQueueList queueEntryList, ServerMessage message)
        {
            super(queueEntryList, message);
        }

        @Override
        public void release()
        {
            super.release();

            discardIfReleasedEntryIsNoLongerLatest();
        }

        @Override
        protected void onDelete()
        {
            if(_latestValueReference != null && _latestValueReference.compareAndSet(this, _deleteInProgress))
            {
                Object key = getMessage().getMessageHeader().getHeader(_conflationKey);
                _latestValuesMap.remove(key,_latestValueReference);
            }

        }

        public void setLatestValueReference(final AtomicReference<ConflationQueueEntry> latestValueReference)
        {
            _latestValueReference = latestValueReference;
        }

        private void discardIfReleasedEntryIsNoLongerLatest()
        {
            if(_latestValueReference != null)
            {
                if(_latestValueReference.get() != this)
                {
                    discardEntry(this);
                }
            }
        }

    }

    /**
     * Exposed purposes of unit test only.
     */
    Map<Object, AtomicReference<ConflationQueueEntry>> getLatestValuesMap()
    {
        return Collections.unmodifiableMap(_latestValuesMap);
    }

    static class Factory implements QueueEntryListFactory<ConflationQueueList.ConflationQueueEntry, ConflationQueue, ConflationQueueList>
    {
        private final String _conflationKey;

        Factory(String conflationKey)
        {
            _conflationKey = conflationKey;
        }

        @Override
        public ConflationQueueList createQueueEntryList(final ConflationQueue queue)
        {
            return new ConflationQueueList(queue, _conflationKey);
        }
    }
}
