/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.util.offheap.unsafe;

import org.gridgain.grid.util.typedef.internal.*;

import java.util.concurrent.atomic.*;

/**
 * Guards concurrent operations on offheap memory to make sure that no thread will access already deallocated pointer.
 * Typical usage is:
 * <pre>
 *     guard.begin();
 *
 *     try {
 *         guard.releaseLater(x);
 *     }
 *     finally {
 *         guard.end();
 *     }
 * </pre>
 *
 * while another thread can safely read the memory being deallocated:
 * <pre>
 *     guard.begin();
 *
 *     try {
 *         mem.readLong(x.getPointer());
 *     }
 *     finally {
 *         guard.end();
 *     }
 * </pre>
 */
public class GridUnsafeGuard {
    /** */
    private final AtomicReference<Operation> head = new AtomicReference<>();

    /** */
    private final AtomicReference<Operation> tail = new AtomicReference<>();

    /** */
    private final ThreadLocal<Operation> currOp = new ThreadLocal<>();

    /**
     * Initialize head and tail with fake operation to avoid {@code null} handling.
     */
    {
        Operation fake = new Operation();

        fake.allowDeallocate();

        head.set(fake);
        tail.set(fake);
    }

    /**
     * Begins concurrent memory operation.
     */
    public void begin() {
        Operation op = currOp.get();

        if (op != null) {
            op.reentries++;

            return;
        }

        op = new Operation();

        currOp.set(op);

        for (;;) {
            Operation prev = head.get();

            op.previous(prev);

            if (head.compareAndSet(prev, op)) {
                prev.next(op);

                break;
            }
        }
    }

    /**
     * Ends concurrent memory operation and releases resources.
     */
    public void end() {
        Operation op = currOp.get();

        assert op != null : "must be called after begin in the same thread";

        if (op.reentries != 0) {
            assert op.reentries > 0 : op.reentries;

            op.reentries--;

            return;
        }

        currOp.remove();

        long curId = op.id;

        op.allowDeallocate();

        // Start deallocating from tail.
        op = tail.get();

        // Go through the inactive ops until find thread-local one.
        while (!op.isActive() && op.id <= curId) {
            op.finish();

            Operation next = op.next;

            if (next == null)
                break;

            op = next;
        }

        // Move tail forward.
        for (;;) {
            Operation t = tail.get();

            if (op.id <= t.id || tail.compareAndSet(t, op))
                break;
        }
    }

    /**
     * Releases memory in the future when it will be safe to do that.
     * Gives no guarantees in which thread it will be executed.
     *
     * @param compound Compound memory.
     */
    public void releaseLater(GridUnsafeCompoundMemory compound) {
        assert currOp.get() != null : "must be called in begin-end block";

        head.get().add(compound);
    }

    /**
     * Does finalization when it will be safe to deallocate offheap memory.
     * Gives no guarantees in which thread it will be executed. Gives
     * no guarantees about execution order of multiple passed finalizers as well.
     *
     * @param finalizer Finalizer.
     */
    public void finalizeLater(Runnable finalizer) {
        assert currOp.get() != null : "must be called in begin-end block";

        head.get().add(new Finalizer(finalizer));
    }

    /**
     * Memory operation which can be executed in parallel with other memory operations.
     */
    @SuppressWarnings("UnusedDeclaration")
    private static class Operation {
        /** */
        private static final int STATE_ACTIVE = 0;

        /** */
        private static final int STATE_MAY_DEALLOCATE = 1;

        /** */
        private static final int STATE_DEALLOCATED = 2;

        /** */
        private static final AtomicReferenceFieldUpdater<Operation, Finalizer> finUpdater =
            AtomicReferenceFieldUpdater.newUpdater(Operation.class, Finalizer.class, "finHead");

        /** */
        private static final AtomicReferenceFieldUpdater<Operation, GridUnsafeCompoundMemory> compoundUpdater =
            AtomicReferenceFieldUpdater.newUpdater(Operation.class, GridUnsafeCompoundMemory.class, "compound");

        /** */
        private static final AtomicIntegerFieldUpdater<Operation> stateUpdater =
            AtomicIntegerFieldUpdater.newUpdater(Operation.class, "state");

        /** */
        private long id;

        /** Reentries of the owner thread. */
        private int reentries;

        /** */
        private volatile Operation next;

        /** */
        private volatile int state;

        /** */
        private volatile Finalizer finHead;

        /** */
        private volatile GridUnsafeCompoundMemory compound;

        /**
         * Adds runnable to the finalization queue.
         *
         * @param fin Finalizer.
         */
        private void add(Finalizer fin) {
            for(;;) {
                Finalizer prev = finHead;

                fin.previous(prev);

                if (finUpdater.compareAndSet(this, prev, fin))
                    break;
            }
        }

        /**
         * Finish operation and release memory.
         */
        private void finish() {
            if (!stateUpdater.compareAndSet(this, STATE_MAY_DEALLOCATE, STATE_DEALLOCATED))
                return;

            GridUnsafeCompoundMemory c = compound;

            if (c != null) {
                c.deallocate();

                compoundUpdater.lazySet(this, null);
            }

            Finalizer fin = finHead;

            if (fin != null) {
                // Need to nullify because last deallocated operation object is still kept in memory.
                finUpdater.lazySet(this, null);

                do {
                    fin.run();

                    fin = fin.previous();
                }
                while(fin != null);
            }
        }

        /**
         * @return {@code true} If memory for this operation was already deallocated.
         */
        private boolean deallocated() {
            return state == STATE_DEALLOCATED;
        }

        /**
         * Adds compound memory for deallocation.
         *
         * @param c Compound memory.
         */
        private void add(GridUnsafeCompoundMemory c) {
            GridUnsafeCompoundMemory existing = compound;

            if (existing == null) {
                if (compoundUpdater.compareAndSet(this, null, c))
                    return;

                existing = compound;
            }

            existing.merge(c);
        }

        /**
         * @param prev Previous operation.
         */
        private void previous(Operation prev) {
            id = prev.id + 1;
        }

        /**
         * Sets flag indicating if memory may be deallocated for this operation.
         */
        private void allowDeallocate() {
            stateUpdater.lazySet(this, STATE_MAY_DEALLOCATE);
        }

        /**
         * @return flag indicating if memory may be deallocated for this operation.
         */
        private boolean isActive() {
            return state == STATE_ACTIVE;
        }

        /**
         * @param next Next operation.
         */
        private void next(Operation next) {
            this.next = next;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(Operation.class, this);
        }
    }

    /**
     * Finalizer.
     */
    private static class Finalizer {
        /** */
        private Finalizer prev;

        /** */
        private final Runnable delegate;

        /**
         * @param delegate Actual finalizer.
         */
        private Finalizer(Runnable delegate) {
            assert delegate != null;

            this.delegate = delegate;
        }

        /**
         * @return Previous finalizer.
         */
        private Finalizer previous() {
            return prev;
        }

        /**
         * @param prev Previous finalizer.
         */
        private void previous(Finalizer prev) {
            this.prev = prev;
        }

        /**
         * Run finalization.
         */
        private void run() {
            delegate.run();
        }
    }
}
