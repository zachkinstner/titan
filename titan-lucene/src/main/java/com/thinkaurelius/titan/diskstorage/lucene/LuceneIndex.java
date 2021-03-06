package com.thinkaurelius.titan.diskstorage.lucene;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.shape.Shape;
import com.thinkaurelius.titan.core.attribute.*;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;
import com.thinkaurelius.titan.diskstorage.TransactionHandle;
import com.thinkaurelius.titan.diskstorage.indexing.IndexEntry;
import com.thinkaurelius.titan.diskstorage.indexing.IndexMutation;
import com.thinkaurelius.titan.diskstorage.indexing.IndexProvider;
import com.thinkaurelius.titan.diskstorage.indexing.IndexQuery;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import com.thinkaurelius.titan.graphdb.query.keycondition.*;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queries.BooleanFilter;
import org.apache.lucene.queries.TermsFilter;
import org.apache.lucene.search.*;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.vector.PointVectorStrategy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class LuceneIndex implements IndexProvider {

    private Logger log = LoggerFactory.getLogger(LuceneIndex.class);


    private static final String DOCID = "_____elementid";
    private static final String GEOID = "_____geo";
    private static final int MAX_STRING_FIELD_LEN = 256;

    private static final int GEO_MAX_LEVELS = 11;

    private final Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_41);

    private final Map<String,IndexWriter> writers = new HashMap<String, IndexWriter>(4);
    private final ReentrantLock writerLock = new ReentrantLock();

    private Map<String,SpatialStrategy> spatial=new ConcurrentHashMap<String, SpatialStrategy>(12);
    private SpatialContext ctx = SpatialContext.GEO;

    private final String basePath;

    public LuceneIndex(Configuration config) {
        String dir = config.getString(GraphDatabaseConfiguration.STORAGE_DIRECTORY_KEY,"");
        Preconditions.checkArgument(StringUtils.isNotBlank(dir),"Need to configure directory for lucene");
        File directory = new File(dir);
        if (!directory.exists()) directory.mkdirs();
        if (!directory.exists() || !directory.isDirectory() || !directory.canWrite()) throw new IllegalArgumentException("Cannot access or write to directory: " + dir);
        basePath = directory.getAbsolutePath();
        log.debug("Configured Lucene to use base directory [{}]",basePath);
    }

    private Directory getStoreDirectory(String store) throws StorageException {
        Preconditions.checkArgument(StringUtils.isAlphanumeric(store),"Invalid store name: %s",store);
        String dir = basePath+File.separator+store;
        try {
            File path = new File(dir);
            if (!path.exists()) path.mkdirs();
            if (!path.exists() || !path.isDirectory() || !path.canWrite()) throw new PermanentStorageException("Cannot access or write to directory: " + dir);
            log.debug("Opening store directory [{}]",path);
            return FSDirectory.open(path);
        } catch (IOException e) {
            throw new PermanentStorageException("Could not open directory: " + dir,e);
        }
    }

    private IndexWriter getWriter(String store) throws StorageException {
        Preconditions.checkArgument(writerLock.isHeldByCurrentThread());
        IndexWriter writer = writers.get(store);
        if (writer==null) {
            IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_41, analyzer);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try {
                writer = new IndexWriter(getStoreDirectory(store), iwc);
                writers.put(store,writer);
            } catch (IOException e) {
                throw new PermanentStorageException("Could not create writer",e);
            }
        }
        return writer;
    }

    private SpatialStrategy getSpatialStrategy(String key) {
        SpatialStrategy strategy = spatial.get(key);
        if (strategy==null) {
            synchronized (spatial) {
                if (!spatial.containsKey(key)) {
//                    SpatialPrefixTree grid = new GeohashPrefixTree(ctx, GEO_MAX_LEVELS);
//                    strategy = new RecursivePrefixTreeStrategy(grid, key);
                    strategy = new PointVectorStrategy(ctx,key);
                    spatial.put(key,strategy);
                } else return spatial.get(key);
            }
        }
        return strategy;
    }

    @Override
    public void register(String store, String key, Class<?> dataType, TransactionHandle tx) throws StorageException {
        //No pre-registration needed for Lucene
    }

    @Override
    public void mutate(Map<String, Map<String, IndexMutation>> mutations, TransactionHandle tx) throws StorageException {
        Transaction ltx = (Transaction)tx;
        writerLock.lock();
        try {
            for (Map.Entry<String,Map<String, IndexMutation>> stores : mutations.entrySet()) {
                String storename = stores.getKey();
                IndexWriter writer = getWriter(storename);
                IndexReader reader = DirectoryReader.open(writer, true);
                IndexSearcher searcher = new IndexSearcher(reader);
                for (Map.Entry<String, IndexMutation> entry : stores.getValue().entrySet()) {
                    String docid = entry.getKey();
                    IndexMutation mutation = entry.getValue();
                    Term docTerm = new Term(DOCID,docid);

                    if (mutation.isDeleted()) {
                        log.trace("Deleted entire document [{}]", docid);
                        writer.deleteDocuments(docTerm);
                        continue;
                    }

                    Document doc=null;
                    TopDocs hits = searcher.search(new TermQuery(docTerm), 10);
                    Map<String,Shape> geofields = Maps.newHashMap();

                    if (hits.scoreDocs.length == 0) {
                        log.trace("Creating new document for [{}]", docid);
                        doc = new Document();
                        Field docidField = new StringField(DOCID, docid, Field.Store.YES);
                        doc.add(docidField);
                    } else if (hits.scoreDocs.length > 1) {
                        throw new IllegalArgumentException("More than one document found for document id: " + docid);
                    } else {
                        log.trace("Updating existing document for [{}]", docid);
                        int docId = hits.scoreDocs[0].doc;
                        //retrieve the old document
                        doc = searcher.doc(docId);
                        for (IndexableField field : doc.getFields()) {
                            if (field.stringValue().startsWith(GEOID)) {
                                geofields.put(field.name(),ctx.readShape(field.stringValue().substring(GEOID.length())));
                            }
                        }
                    }
                    Preconditions.checkNotNull(doc);
                    for (String key : mutation.getDeletions()) {
                        if (doc.getField(key)!=null) {
                            log.trace("Removing field [{}] on document [{}]", key, docid);
                            doc.removeFields(key);
                            geofields.remove(key);
                        }
                    }
                    for (IndexEntry add : mutation.getAdditions()) {
                        log.trace("Adding field [{}] on document [{}]", add.key, docid);
                        if (doc.getField(add.key)!=null) doc.removeFields(add.key);
                        if (add.value instanceof Number) {
                            Field field = null;
                            if (add.value instanceof Integer || add.value instanceof Long) {
                                field = new LongField(add.key, ((Number)add.value).longValue(), Field.Store.YES);
                            } else { //double or float
                                field = new DoubleField(add.key, ((Number)add.value).doubleValue(), Field.Store.YES);
                            }
                            doc.add(field);
                        } else if (add.value instanceof String) {
                            String str = (String)add.value;
                            Field field = new TextField(add.key, str, Field.Store.YES);
                            doc.add(field);
//                            if (str.length()<MAX_STRING_FIELD_LEN)
//                                field = new StringField(add.key+STR_SUFFIX, str, Field.Store.NO);
//                            doc.add(field);
                        } else if (add.value instanceof Geoshape) {
                            Shape shape = ((Geoshape)add.value).convert2Spatial4j();
                            geofields.put(add.key,shape);
                            doc.add(new StoredField(add.key,GEOID+ctx.toString(shape)));

                        } else throw new IllegalArgumentException("Unsupported type: " + add.value);
                    }
                    for (Map.Entry<String,Shape> geo : geofields.entrySet()) {
                        log.trace("Updating geo-indexes for key {}",geo.getKey());
                        for (IndexableField f : getSpatialStrategy(geo.getKey()).createIndexableFields(geo.getValue())) {
                            doc.add(f);
                        }
                    }

                    //write the old document to the index with the modifications
                    writer.updateDocument(new Term(DOCID,docid), doc);
                }
                writer.commit();
            }
            ltx.postCommit();
        } catch (IOException e) {
            throw new TemporaryStorageException("Could not update Lucene index",e);
        } finally {
            writerLock.unlock();
        }
    }

    @Override
    public List<String> query(IndexQuery query, TransactionHandle tx) throws StorageException {
        //Construct query
        Filter q = convertQuery(query.getCondition());

        try {
            IndexSearcher searcher = ((Transaction)tx).getSearcher(query.getStore());
            if (searcher==null) return ImmutableList.of(); //Index does not yet exist
            long time = System.currentTimeMillis();
            TopDocs docs = searcher.search(new MatchAllDocsQuery(), q, query.hasLimit()?query.getLimit():Integer.MAX_VALUE-1);
            log.debug("Executed query [{}] in {} ms",q,System.currentTimeMillis()-time);
            List<String> result = new ArrayList<String>(docs.scoreDocs.length);
            for (int i = 0; i < docs.scoreDocs.length; i++) {
                result.add(searcher.doc(docs.scoreDocs[i].doc).getField(DOCID).stringValue());
            }
            return result;
        } catch (IOException e) {
            throw new TemporaryStorageException("Could not execute Lucene query",e);
        }
    }

    private static final Filter numericFilter(String key, Cmp relation, Number value) {
        switch (relation) {
            case EQUAL:
                return (value instanceof Long || value instanceof Integer)?
                        NumericRangeFilter.newLongRange(key,value.longValue(),value.longValue(),true,true):
                        NumericRangeFilter.newDoubleRange(key,value.doubleValue(),value.doubleValue(),true,true);
            case NOT_EQUAL:
                BooleanFilter q = new BooleanFilter();
                if (value instanceof Long || value instanceof Integer) {
                    q.add(NumericRangeFilter.newLongRange(key,Long.MIN_VALUE,value.longValue(),true,false), BooleanClause.Occur.SHOULD);
                    q.add(NumericRangeFilter.newLongRange(key,value.longValue(),Long.MAX_VALUE,false,true), BooleanClause.Occur.SHOULD);
                } else {
                    q.add(NumericRangeFilter.newDoubleRange(key, Double.MIN_VALUE, value.doubleValue(), true, false), BooleanClause.Occur.SHOULD);
                    q.add(NumericRangeFilter.newDoubleRange(key, value.doubleValue(), Double.MAX_VALUE, false, true), BooleanClause.Occur.SHOULD);
                }
                return q;
            case LESS_THAN:
                return (value instanceof Long || value instanceof Integer)?
                        NumericRangeFilter.newLongRange(key,Long.MIN_VALUE,value.longValue(),true,false):
                        NumericRangeFilter.newDoubleRange(key,Double.MIN_VALUE,value.doubleValue(),true,false);
            case LESS_THAN_EQUAL:
                return (value instanceof Long || value instanceof Integer)?
                        NumericRangeFilter.newLongRange(key,Long.MIN_VALUE,value.longValue(),true,true):
                        NumericRangeFilter.newDoubleRange(key,Double.MIN_VALUE,value.doubleValue(),true,true);
            case GREATER_THAN:
                return (value instanceof Long || value instanceof Integer)?
                        NumericRangeFilter.newLongRange(key,value.longValue(),Long.MAX_VALUE,false,true):
                        NumericRangeFilter.newDoubleRange(key,value.doubleValue(),Double.MAX_VALUE,false,true);
            case GREATER_THAN_EQUAL:
                return (value instanceof Long || value instanceof Integer)?
                        NumericRangeFilter.newLongRange(key,value.longValue(),Long.MAX_VALUE,true,true):
                        NumericRangeFilter.newDoubleRange(key,value.doubleValue(),Double.MAX_VALUE,true,true);
            default: throw new IllegalArgumentException("Unexpected relation: " + relation);
        }
    }

    private final Filter convertQuery(KeyCondition<String> condition) {
        if (condition instanceof KeyAtom) {
            KeyAtom<String> atom = (KeyAtom<String>) condition;
            Object value = atom.getCondition();
            String key = atom.getKey();
            Relation relation = atom.getRelation();
            if (value instanceof Number || value instanceof Interval) {
                Preconditions.checkArgument(relation instanceof Cmp,"Relation not supported on numeric types: " + relation);
                if (relation==Cmp.INTERVAL) {
                    Preconditions.checkArgument(value instanceof Interval && ((Interval)value).getStart() instanceof Number);
                    Interval i = (Interval)value;
                    if (i.getStart() instanceof Long || i.getStart() instanceof Integer) {
                        return NumericRangeFilter.newLongRange(key,((Number)i.getStart()).longValue(),((Number)i.getEnd()).longValue(),i.startInclusive(),i.endInclusive());
                    } else {
                        return NumericRangeFilter.newDoubleRange(key,((Number)i.getStart()).doubleValue(),((Number)i.getEnd()).doubleValue(),i.startInclusive(),i.endInclusive());
                    }
                } else {
                    Preconditions.checkArgument(value instanceof Number);
                    return numericFilter(key,(Cmp)relation,(Number)value);
                }
            } else if (value instanceof String) {
                if (relation == Text.CONTAINS) {
                    return new TermsFilter(new Term(key,((String)value).toLowerCase()));
//                } else if (relation == Txt.PREFIX) {
//                    return new PrefixFilter(new Term(key+STR_SUFFIX,(String)value));
//                } else if (relation == Cmp.EQUAL) {
//                    return new TermsFilter(new Term(key+STR_SUFFIX,(String)value));
//                } else if (relation == Cmp.NOT_EQUAL) {
//                    BooleanFilter q = new BooleanFilter();
//                    q.add(new TermsFilter(new Term(key+STR_SUFFIX,(String)value)), BooleanClause.Occur.MUST_NOT);
//                    return q;
                } else throw new IllegalArgumentException("Relation is not supported for string value: " + relation);
            } else if (value instanceof Geoshape) {
                Preconditions.checkArgument(relation==Geo.WITHIN,"Relation is not supported for geo value: " + relation);
                Shape shape = ((Geoshape)value).convert2Spatial4j();
                SpatialArgs args = new SpatialArgs(SpatialOperation.IsWithin,shape);
                return getSpatialStrategy(key).makeFilter(args);
            } else throw new IllegalArgumentException("Unsupported type: " + value);
        } else if (condition instanceof KeyNot) {
            BooleanFilter q = new BooleanFilter();
            q.add(convertQuery(((KeyNot<String>)condition).getChild()), BooleanClause.Occur.MUST_NOT);
            return q;
        } else if (condition instanceof KeyAnd) {
            BooleanFilter q = new BooleanFilter();
            for (KeyCondition<String> c : condition.getChildren()) {
                q.add(convertQuery(c), BooleanClause.Occur.MUST);
            }
            return q;
        } else if (condition instanceof KeyOr) {
            BooleanFilter q = new BooleanFilter();
            for (KeyCondition<String> c : condition.getChildren()) {
                q.add(convertQuery(c), BooleanClause.Occur.SHOULD);
            }
            return q;
        } else throw new IllegalArgumentException("Invalid condition: " + condition);
    }

    @Override
    public TransactionHandle beginTransaction() throws StorageException {
        return new Transaction();
    }

    @Override
    public boolean supports(Class<?> dataType, Relation relation) {
        if (Number.class.isAssignableFrom(dataType)) {
            for (Cmp cmp : Cmp.values()) if (relation==cmp) return true;
            return false;
        } else if (dataType == Geoshape.class) {
            return relation== Geo.WITHIN;
        } else if (dataType == String.class) {
            return relation == Text.CONTAINS; // || relation == Txt.PREFIX || relation == Cmp.EQUAL || relation == Cmp.NOT_EQUAL;
        } else return false;
    }

    @Override
    public boolean supports(Class<?> dataType) {
        if (Number.class.isAssignableFrom(dataType) || dataType== Geoshape.class || dataType==String.class) return true;
        else return false;
    }

    @Override
    public void close() throws StorageException {
        try {
            for (IndexWriter w : writers.values()) w.close();
        } catch (IOException e) {
            throw new PermanentStorageException("Could not close writers",e);
        }
    }

    @Override
    public void clearStorage() throws StorageException {
        try {
            FileUtils.deleteDirectory(new File(basePath));
        } catch (IOException e) {
            throw new PermanentStorageException("Could not delete lucene directory: " + basePath,e);
        }
    }

    private class Transaction implements TransactionHandle {

        private final Set<String> updatedStores = Sets.newHashSet();


        private final Map<String,IndexSearcher> searchers = new HashMap<String,IndexSearcher>(4);


        private synchronized IndexSearcher getSearcher(String store) throws StorageException {
            IndexSearcher searcher = searchers.get(store);
            if (searcher==null) {
                IndexReader reader = null;
                try {
                    reader = DirectoryReader.open(getStoreDirectory(store));
                    searcher = new IndexSearcher(reader);
                } catch (IndexNotFoundException e) {
                    searcher = null;
                } catch (IOException e) {
                    throw new PermanentStorageException("Could not open index reader on store: " + store,e);
                }
                searchers.put(store,searcher);
            }
            return searcher;
        }

        public void postCommit() throws StorageException {
            close();
            searchers.clear();
        }


        @Override
        public void commit() throws StorageException {
            close();
        }

        @Override
        public void rollback() throws StorageException {
            close();
        }

        @Override
        public void flush() throws StorageException {

        }

        private void close() throws StorageException {
            try {
                for (IndexSearcher searcher : searchers.values()) {
                    if (searcher!=null) searcher.getIndexReader().close();
                }
            }  catch (IOException e) {
                throw new PermanentStorageException("Could not close searcher",e);
            }
        }
    }

}
