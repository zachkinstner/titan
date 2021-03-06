package com.thinkaurelius.titan.diskstorage.cassandra.astyanax;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.netflix.astyanax.ExceptionCallback;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.*;
import com.netflix.astyanax.query.AllRowsQuery;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.retry.RetryPolicy;
import com.netflix.astyanax.serializers.ByteBufferSerializer;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.util.RecordIterator;
import com.thinkaurelius.titan.diskstorage.util.StaticByteBuffer;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.thinkaurelius.titan.diskstorage.cassandra.AbstractCassandraStoreManager.Partitioner;
import static com.thinkaurelius.titan.diskstorage.cassandra.CassandraTransaction.getTx;

public class AstyanaxOrderedKeyColumnValueStore implements
        KeyColumnValueStore {

    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);


    private final Keyspace keyspace;
    private final String columnFamilyName;
    private final ColumnFamily<ByteBuffer, ByteBuffer> columnFamily;
    private final RetryPolicy retryPolicy;
    private final AstyanaxStoreManager storeManager;


    AstyanaxOrderedKeyColumnValueStore(String columnFamilyName,
                                       Keyspace keyspace,
                                       AstyanaxStoreManager storeManager,
                                       RetryPolicy retryPolicy) {
        this.keyspace = keyspace;
        this.columnFamilyName = columnFamilyName;
        this.retryPolicy = retryPolicy;
        this.storeManager = storeManager;

        columnFamily = new ColumnFamily<ByteBuffer, ByteBuffer>(
                this.columnFamilyName,
                ByteBufferSerializer.get(),
                ByteBufferSerializer.get());
    }


    ColumnFamily<ByteBuffer, ByteBuffer> getColumnFamily() {
        return columnFamily;
    }

    @Override
    public void close() throws StorageException {
        //Do nothing
    }

    @Override
    public boolean containsKey(StaticBuffer key, StoreTransaction txh) throws StorageException {
        try {
            // See getSlice() below for a warning suppression justification
            @SuppressWarnings("rawtypes")
            RowQuery rq = (RowQuery) keyspace.prepareQuery(columnFamily)
                    .withRetryPolicy(retryPolicy.duplicate())
                    .setConsistencyLevel(getTx(txh).getReadConsistencyLevel().getAstyanaxConsistency())
                    .getKey(key.asByteBuffer());
            @SuppressWarnings("unchecked")
            OperationResult<ColumnList<ByteBuffer>> r = rq.withColumnRange(EMPTY, EMPTY, false, 1).execute();
            return 0 < r.getResult().size();
        } catch (ConnectionException e) {
            throw new TemporaryStorageException(e);
        }
    }

    @Override
    public List<Entry> getSlice(KeySliceQuery query, StoreTransaction txh) throws StorageException {

        /*
         * RowQuery<K,C> should be parameterized as
         * RowQuery<ByteBuffer,ByteBuffer>. However, this causes the following
         * compilation error when attempting to call withColumnRange on a
         * RowQuery<ByteBuffer,ByteBuffer> instance:
         * 
         * java.lang.Error: Unresolved compilation problem: The method
         * withColumnRange(ByteBuffer, ByteBuffer, boolean, int) is ambiguous
         * for the type RowQuery<ByteBuffer,ByteBuffer>
         * 
         * The compiler substitutes ByteBuffer=C for both startColumn and
         * endColumn, compares it to its identical twin with that type
         * hard-coded, and dies.
         * 
         */
        RowQuery rq = keyspace.prepareQuery(columnFamily)
                .setConsistencyLevel(getTx(txh).getReadConsistencyLevel().getAstyanaxConsistency())
                .withRetryPolicy(retryPolicy.duplicate())
                .getKey(query.getKey().asByteBuffer());
        int limit = Integer.MAX_VALUE - 1;
        if (query.hasLimit()) limit = query.getLimit();
        rq.withColumnRange(query.getSliceStart().asByteBuffer(), query.getSliceEnd().asByteBuffer(), false, limit + 1);

        OperationResult<ColumnList<ByteBuffer>> r;
        try {
            @SuppressWarnings("unchecked")
            OperationResult<ColumnList<ByteBuffer>> tmp = (OperationResult<ColumnList<ByteBuffer>>) rq.execute();
            r = tmp;
        } catch (ConnectionException e) {
            throw new TemporaryStorageException(e);
        }

        List<Entry> result = new ArrayList<Entry>(r.getResult().size());

        int i = 0;

        ByteBuffer sliceEndBB = query.getSliceEnd().asByteBuffer();
        
        for (Column<ByteBuffer> c : r.getResult()) {
            ByteBuffer colName = c.getName();

            // Cassandra treats the end of a slice column range inclusively, but
            // this method's contract promises to treat it exclusively. Check
            // for the final column in the Cassandra results and skip it if
            // found.
            if (colName.equals(sliceEndBB)) {
                break;
            }

            result.add(new ByteBufferEntry(colName, c.getByteBufferValue()));

            if (++i == limit) {
                break;
            }
        }

        return result;
    }

    @Override
    public void mutate(StaticBuffer key, List<Entry> additions, List<StaticBuffer> deletions, StoreTransaction txh) throws StorageException {
        mutateMany(ImmutableMap.of(key, new KCVMutation(additions, deletions)), txh);
    }

    public void mutateMany(Map<StaticBuffer, KCVMutation> mutations, StoreTransaction txh) throws StorageException {
        storeManager.mutateMany(ImmutableMap.of(columnFamilyName, mutations), txh);
    }

    @Override
    public void acquireLock(StaticBuffer key, StaticBuffer column, StaticBuffer expectedValue, StoreTransaction txh) throws StorageException {
        throw new UnsupportedOperationException();
    }

    @Override
    public RecordIterator<StaticBuffer> getKeys(StoreTransaction txh) throws StorageException {
        if (storeManager.getPartitioner() != Partitioner.RANDOM)
            throw new PermanentStorageException("This operation is only allowed when random partitioner (md5 or murmur3) is used.");;

        AllRowsQuery<ByteBuffer, ByteBuffer> allRowsQuery = keyspace.prepareQuery(columnFamily).getAllRows();

        Rows<ByteBuffer, ByteBuffer> result;
        try {
            /* Note: we need to fetch columns for each row as well to remove "range ghosts" */
            result = allRowsQuery.setRowLimit(storeManager.getPageSize()) // pre-fetch that many rows at a time
                               .setConcurrencyLevel(1) // one execution thread for fetching portion of rows
                               .setExceptionCallback(new ExceptionCallback() {
                                   private int retries = 0;

                                   @Override
                                   public boolean onException(ConnectionException e) {
                                       try {
                                           return retries > 2; // make 3 re-tries
                                       } finally {
                                           retries++;
                                       }
                                   }
                               })
                               .execute().getResult();
        } catch (ConnectionException e) {
            throw new PermanentStorageException(e);
        }

        final Iterator<Row<ByteBuffer, ByteBuffer>> rows = Iterators.filter(result.iterator(), new KeyIterationPredicate());

        return new RecordIterator<StaticBuffer>() {
            @Override
            public boolean hasNext() throws StorageException {
                return rows.hasNext();
            }

            @Override
            public StaticBuffer next() throws StorageException {
                return new StaticByteBuffer(rows.next().getKey());
            }

            @Override
            public void close() throws StorageException {
                // nothing to clean-up here
            }
        };
    }

    @Override
    public StaticBuffer[] getLocalKeyPartition() throws StorageException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getName() {
        return columnFamilyName;
    }

    private static class KeyIterationPredicate implements Predicate<Row<ByteBuffer, ByteBuffer>> {
        @Override
        public boolean apply(@Nullable Row<ByteBuffer, ByteBuffer> row) {
            return (row == null) ? false : row.getColumns().size() > 0;
        }
    }
}
