/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.pool;

import io.questdb.MessageBus;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.EntryUnavailableException;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.pool.ex.EntryLockedException;
import io.questdb.cairo.pool.ex.PoolClosedException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.ConcurrentHashMap;
import io.questdb.std.Os;
import io.questdb.std.Unsafe;

import java.util.Arrays;
import java.util.Map;

public class ReaderPool extends AbstractPool implements ResourcePool<TableReader> {

    public static final int ENTRY_SIZE = 32;
    private static final long LOCK_OWNER = Unsafe.getFieldOffset(Entry.class, "lockOwner");
    private static final Log LOG = LogFactory.getLog(ReaderPool.class);
    private static final int NEXT_ALLOCATED = 1;
    private static final int NEXT_LOCKED = 2;
    private static final int NEXT_OPEN = 0;
    private static final long NEXT_STATUS = Unsafe.getFieldOffset(Entry.class, "nextStatus");
    private static final long UNLOCKED = -1L;
    private final ConcurrentHashMap<Entry> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final int maxSegments;
    private final MessageBus messageBus;

    public ReaderPool(CairoConfiguration configuration, MessageBus messageBus) {
        super(configuration, configuration.getInactiveReaderTTL());
        this.maxSegments = configuration.getReaderPoolMaxSegments();
        this.messageBus = messageBus;
        this.maxEntries = maxSegments * ENTRY_SIZE;
    }

    public Map<CharSequence, Entry> entries() {
        return entries;
    }

    @Override
    public TableReader get(CharSequence name) {

        Entry e = getEntry(name);

        long lockOwner = e.lockOwner;
        long thread = Thread.currentThread().getId();

        if (lockOwner != UNLOCKED) {
            LOG.info().$('\'').utf8(name).$("' is locked [owner=").$(lockOwner).$(']').$();
            throw EntryLockedException.instance("unknown");
        }

        do {
            for (int i = 0; i < ENTRY_SIZE; i++) {
                if (Unsafe.cas(e.allocations, i, UNALLOCATED, thread)) {
                    Unsafe.arrayPutOrdered(e.releaseOrAcquireTimes, i, clock.getTicks());
                    // got lock, allocate if needed
                    R r = e.getReader(i);
                    if (r == null) {
                        try {
                            LOG.info()
                                    .$("open '").utf8(name)
                                    .$("' [at=").$(e.index).$(':').$(i)
                                    .$(']').$();
                            r = new R(this, e, i, name, messageBus);
                        } catch (CairoException ex) {
                            Unsafe.arrayPutOrdered(e.allocations, i, UNALLOCATED);
                            throw ex;
                        }

                        e.setReader(i, r);
                        notifyListener(thread, name, PoolListener.EV_CREATE, e.index, i);
                    } else {
                        try {
                            r.goActive();
                        } catch (Throwable ex) {
                            r.close();
                            throw ex;
                        }
                        notifyListener(thread, name, PoolListener.EV_GET, e.index, i);
                    }

                    if (isClosed()) {
                        e.setReader(i, null);
                        r.goodbye();
                        LOG.info().$('\'').utf8(name).$("' born free").$();
                        return r;
                    }
                    LOG.debug().$('\'').utf8(name).$("' is assigned [at=").$(e.index).$(':').$(i).$(", thread=").$(thread).$(']').$();
                    return r;
                }
            }

            LOG.debug().$("Thread ").$(thread).$(" is moving to entry ").$(e.index + 1).$();

            // all allocated, create next entry if possible
            if (Unsafe.getUnsafe().compareAndSwapInt(e, NEXT_STATUS, NEXT_OPEN, NEXT_ALLOCATED)) {
                LOG.debug().$("Thread ").$(thread).$(" allocated entry ").$(e.index + 1).$();
                e.next = new Entry(e.index + 1, clock.getTicks());
            }
            e = e.next;
        } while (e != null && e.index < maxSegments);

        // max entries exceeded
        notifyListener(thread, name, PoolListener.EV_FULL, -1, -1);
        LOG.info().$("could not get, busy [table=`").utf8(name).$("`, thread=").$(thread).$(", retries=").$(this.maxSegments).$(']').$();
        throw EntryUnavailableException.instance("unknown");
    }

    public int getBusyCount() {
        int count = 0;
        for (Map.Entry<CharSequence, Entry> me : entries.entrySet()) {
            Entry e = me.getValue();
            do {
                for (int i = 0; i < ENTRY_SIZE; i++) {
                    if (Unsafe.arrayGetVolatile(e.allocations, i) != UNALLOCATED && e.readers[i] != null) {
                        count++;
                    }
                }
                e = e.next;
            } while (e != null);
        }
        return count;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public boolean lock(CharSequence name) {
        Entry e = getEntry(name);
        final long thread = Thread.currentThread().getId();
        if (Unsafe.cas(e, LOCK_OWNER, UNLOCKED, thread) || (e.lockOwner == thread)) {
            do {
                for (int i = 0; i < ENTRY_SIZE; i++) {
                    if (Unsafe.cas(e.allocations, i, UNALLOCATED, thread)) {
                        closeReader(thread, e, i, PoolListener.EV_LOCK_CLOSE, PoolConstants.CR_NAME_LOCK);
                    } else if (Unsafe.arrayGetVolatile(e.allocations, i) == thread) {
                        // same thread, don't need to order reads
                        if (e.readers[i] != null) {
                            // this thread has busy reader, it should close first
                            e.lockOwner = -1L;
                            return false;
                        }
                    } else {
                        LOG.info().$("could not lock, busy [table=`").utf8(name).$("`, at=").$(e.index).$(':').$(i).$(", owner=").$(e.allocations[i]).$(", thread=").$(thread).$(']').$();
                        e.lockOwner = -1L;
                        return false;
                    }
                }

                // try to prevent new entries from being created
                if (e.next == null) {
                    if (Unsafe.getUnsafe().compareAndSwapInt(e, NEXT_STATUS, NEXT_OPEN, NEXT_LOCKED)) {
                        break;
                    } else if (e.nextStatus == NEXT_ALLOCATED) {
                        // now we must wait until another thread that executes a get() call
                        // assigns the newly created next entry
                        while (e.next == null) {
                            Os.pause();
                        }
                    }
                }

                e = e.next;
            } while (e != null);
        } else {
            LOG.error().$("' already locked [table=`").utf8(name).$("`, owner=").$(e.lockOwner).$(']').$();
            notifyListener(thread, name, PoolListener.EV_LOCK_BUSY, -1, -1);
            return false;
        }
        notifyListener(thread, name, PoolListener.EV_LOCK_SUCCESS, -1, -1);
        LOG.debug().$("locked [table=`").utf8(name).$("`, thread=").$(thread).$(']').$();
        return true;
    }

    public void unlock(CharSequence name) {
        Entry e = entries.get(name);
        long thread = Thread.currentThread().getId();
        if (e == null) {
            LOG.info().$("not found, cannot unlock [table=`").$(name).$("`]").$();
            notifyListener(thread, name, PoolListener.EV_NOT_LOCKED, -1, -1);
            return;
        }

        if (e.lockOwner == thread) {
            entries.remove(name);
            while (e != null) {
                e = e.next;
            }
        } else {
            notifyListener(thread, name, PoolListener.EV_NOT_LOCK_OWNER);
            throw CairoException.nonCritical().put("Not the lock owner of ").put(name);
        }

        notifyListener(thread, name, PoolListener.EV_UNLOCKED, -1, -1);
        LOG.debug().$("unlocked [table=`").utf8(name).$("`]").$();
    }

    private void checkClosed() {
        if (isClosed()) {
            LOG.info().$("is closed").$();
            throw PoolClosedException.INSTANCE;
        }
    }

    private void closeReader(long thread, Entry entry, int index, short ev, int reason) {
        R r = entry.getReader(index);
        if (r != null) {
            r.goodbye();
            r.close();
            LOG.info().$("closed '").utf8(r.getTableName())
                    .$("' [at=").$(entry.index).$(':').$(index)
                    .$(", reason=").$(PoolConstants.closeReasonText(reason))
                    .I$();
            notifyListener(thread, r.getTableName(), ev, entry.index, index);
            entry.setReader(index, null);
        }
    }

    private Entry getEntry(CharSequence name) {
        checkClosed();

        Entry e = entries.get(name);
        if (e == null) {
            e = new Entry(0, clock.getTicks());
            Entry other = entries.putIfAbsent(name, e);
            if (other != null) {
                e = other;
            }
        }
        return e;
    }

    private void notifyListener(long thread, CharSequence name, short event, int segment, int position) {
        PoolListener listener = getPoolListener();
        if (listener != null) {
            listener.onEvent(PoolListener.SRC_READER, thread, name, event, (short) segment, (short) position);
        }
    }

    private boolean returnToPool(R reader) {
        CharSequence name = reader.getTableName();

        long thread = Thread.currentThread().getId();

        int index = reader.index;
        final ReaderPool.Entry e = reader.entry;
        if (e == null) {
            return false;
        }

        final long owner = Unsafe.arrayGetVolatile(e.allocations, index);
        if (owner != UNALLOCATED) {

            LOG.debug().$('\'').$(name).$("' is back [at=").$(e.index).$(':').$(index).$(", thread=").$(thread).$(']').$();
            notifyListener(thread, name, PoolListener.EV_RETURN, e.index, index);

            // release the entry for anyone to pick up
            e.releaseOrAcquireTimes[index] = clock.getTicks();
            Unsafe.arrayPutOrdered(e.allocations, index, UNALLOCATED);
            final boolean closed = isClosed();

            // When pool is closed we will race against release thread
            // to release our entry. No need to bother releasing map entry, pool is going down.
            return !closed || !Unsafe.cas(e.allocations, index, UNALLOCATED, owner);
        }

        throw CairoException.critical(0).put("double close [table=").put(name).put(", index=").put(index).put(']');
    }

    @Override
    protected void closePool() {
        super.closePool();
        LOG.info().$("closed").$();
    }

    @Override
    protected boolean releaseAll(long deadline) {
        long thread = Thread.currentThread().getId();
        boolean removed = false;
        int casFailures = 0;
        int closeReason = deadline < Long.MAX_VALUE ? PoolConstants.CR_IDLE : PoolConstants.CR_POOL_CLOSE;

        for (Entry e : entries.values()) {
            do {
                for (int i = 0; i < ENTRY_SIZE; i++) {
                    R r;
                    if (deadline > Unsafe.arrayGetVolatile(e.releaseOrAcquireTimes, i) && (r = e.getReader(i)) != null) {
                        if (Unsafe.cas(e.allocations, i, UNALLOCATED, thread)) {
                            // check if deadline violation still holds
                            if (deadline > e.releaseOrAcquireTimes[i]) {
                                removed = true;
                                closeReader(thread, e, i, PoolListener.EV_EXPIRE, closeReason);
                            }
                            Unsafe.arrayPutOrdered(e.allocations, i, UNALLOCATED);
                        } else {
                            casFailures++;
                            if (deadline == Long.MAX_VALUE) {
                                r.goodbye();
                                LOG.info().$("shutting down. '").$(r.getTableName()).$("' is left behind").$();
                            }
                        }
                    }
                }
                // this does not release the next
                e = e.next;
            } while (e != null);
        }

        // when we are timing out entries the result is "true" if there was any work done
        // when we're closing pool, the result is true when pool is empty
        if (closeReason == PoolConstants.CR_IDLE) {
            return removed;
        } else {
            return casFailures == 0;
        }
    }

    public static final class Entry {
        final long[] allocations = new long[ENTRY_SIZE];
        final int index;
        final Object[] readers = new Object[ENTRY_SIZE];
        final long[] releaseOrAcquireTimes = new long[ENTRY_SIZE];
        volatile long lockOwner = -1L;
        volatile Entry next;
        volatile int nextStatus = NEXT_OPEN;

        public Entry(int index, long currentMicros) {
            this.index = index;
            Arrays.fill(allocations, UNALLOCATED);
            Arrays.fill(releaseOrAcquireTimes, currentMicros);
        }

        public Entry getNext() {
            return next;
        }

        public long getOwnerVolatile(int pos) {
            return Unsafe.arrayGetVolatile(allocations, pos);
        }

        public R getReader(int pos) {
            return (R) readers[pos];
        }

        public long getReleaseOrAcquireTime(int pos) {
            return releaseOrAcquireTimes[pos];
        }

        public void setReader(int pos, R reader) {
            readers[pos] = reader;
        }
    }

    public static class R extends TableReader {
        private final int index;
        private Entry entry;
        private ReaderPool pool;

        public R(ReaderPool pool, Entry entry, int index, CharSequence name, MessageBus messageBus) {
            super(pool.getConfiguration(), name, messageBus);
            this.pool = pool;
            this.entry = entry;
            this.index = index;
        }

        @Override
        public void close() {
            if (isOpen()) {
                goPassive();
                final ReaderPool pool = this.pool;
                if (pool != null && entry != null) {
                    if (pool.returnToPool(this)) {
                        return;
                    }
                }
                super.close();
            }
        }

        private void goodbye() {
            entry = null;
            pool = null;
        }
    }
}