package org.elasticsearch.river.mongodb;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.BasicBSONList;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.river.mongodb.MongoDBRiver.QueueEntry;
import org.elasticsearch.river.mongodb.util.MongoDBHelper;
import org.elasticsearch.river.mongodb.util.MongoDBRiverHelper;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHit;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.DBRef;
import com.mongodb.gridfs.GridFSDBFile;

class Indexer implements Runnable {

    private final ESLogger logger = ESLoggerFactory.getLogger(this.getClass().getName());
    private final MongoDBRiver river;
    private final MongoDBRiverDefinition definition;
    private final SharedContext context;
    private final Client client;
    private final ScriptService scriptService;

    private final Map<SimpleEntry<String, String>, MongoDBRiverBulkProcessor> processors = Maps.newHashMap();

    public Indexer(MongoDBRiver river, MongoDBRiverDefinition definition, SharedContext context, Client client, ScriptService scriptService) {
        this.river = river;
        this.definition = definition;
        this.context = context;
        this.client = client;
        this.scriptService = scriptService;
        logger.debug(
                "Create bulk processor with parameters - bulk actions: {} - concurrent request: {} - flush interval: {} - bulk size: {}",
                definition.getBulk().getBulkActions(), definition.getBulk().getConcurrentRequests(), definition.getBulk()
                        .getFlushInterval(), definition.getBulk().getBulkSize());
        getBulkProcessor(definition.getIndexName(), definition.getTypeName());
    }

    @Override
    public void run() {
        while (context.getStatus() == Status.RUNNING) {

            try {
                Timestamp<?> lastTimestamp = null;

                // 1. Attempt to fill as much of the bulk request as possible
                QueueEntry entry = context.getStream().take();
                lastTimestamp = processBlockingQueue(entry);
                while ((entry = context.getStream().poll(definition.getBulk().getFlushInterval().millis(), MILLISECONDS)) != null) {
                    lastTimestamp = processBlockingQueue(entry);
                }

                // 2. Update the timestamp
                if (lastTimestamp != null) {
                    MongoDBRiver.setLastTimestamp(definition, lastTimestamp,
                            getBulkProcessor(definition.getIndexName(), definition.getTypeName()).getBulkProcessor());
                }

            } catch (InterruptedException e) {
                logger.info("river-mongodb indexer interrupted");
                releaseProcessors();
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private MongoDBRiverBulkProcessor getBulkProcessor(String index, String type) {
        SimpleEntry<String, String> entry = new SimpleEntry<String, String>(index, type);
        if (!processors.containsKey(entry)) {
            processors.put(new SimpleEntry<String, String>(index, type), new MongoDBRiverBulkProcessor.Builder(river, definition, client,
                    index, type).build());
        }
        return processors.get(entry);
    }

    private void releaseProcessors() {
        for (MongoDBRiverBulkProcessor processor : processors.values()) {
            processor.getBulkProcessor().close();
        }
        processors.clear();
    }

    @SuppressWarnings({ "unchecked" })
    private Timestamp<?> processBlockingQueue(QueueEntry entry) {
        Operation operation = entry.getOperation();
        if (entry.getData().get(MongoDBRiver.MONGODB_ID_FIELD) == null
                && (operation == Operation.INSERT || operation == Operation.UPDATE || operation == Operation.DELETE)) {
            logger.warn("Cannot get object id. Skip the current item: [{}]", entry.getData());
            return null;
        }

        Timestamp<?> lastTimestamp = entry.getOplogTimestamp();
        String type;
        if (definition.isImportAllCollections()) {
            type = entry.getCollection();
        } else {
            type = definition.getTypeName();
        }
        if (MongoDBRiver.OPLOG_COMMAND_OPERATION.equals(operation)) {
            try {
                updateBulkRequest(entry.getData(), null, operation, definition.getIndexName(), type, null, null);
            } catch (IOException ioEx) {
                logger.error("Update bulk failed.", ioEx);
            }
            return lastTimestamp;
        }

        String objectId = "";
        if (entry.getData().get(MongoDBRiver.MONGODB_ID_FIELD) != null) {
            objectId = entry.getData().get(MongoDBRiver.MONGODB_ID_FIELD).toString();
        }

        // TODO: Should the river support script filter,
        // advanced_transformation, include_collection for GridFS?
        if (entry.isAttachment()) {
            try {
                updateBulkRequest(entry.getData(), objectId, operation, definition.getIndexName(), type, null, null);
            } catch (IOException ioEx) {
                logger.error("Update bulk failed.", ioEx);
            }
            return lastTimestamp;
        }

        if (hasScript() && definition.isAdvancedTransformation()) {
            return applyAdvancedTransformation(entry, type);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("updateBulkRequest for id: [{}], operation: [{}]", objectId, operation);
        }

        if (!definition.getIncludeCollection().isEmpty()) {
            logger.trace("About to include collection. set attribute {} / {} ", definition.getIncludeCollection(),
                    definition.getMongoCollection());
            entry.getData().put(definition.getIncludeCollection(), definition.getMongoCollection());
        }

        Map<String, Object> ctx = new HashMap();
        Map<String, Object> data = entry.getData().toMap();
        if (hasScript()) {
            if (ctx != null) {
                ctx.put("document", entry.getData());
                ctx.put("operation", operation.getValue());
                if (!objectId.isEmpty()) {
                    ctx.put("id", objectId);
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("Script to be executed: {} - {}", definition.getScriptType(), definition.getScript());
                    logger.trace("Context before script executed: {}", ctx);
                }
                try {
                    ExecutableScript executableScript = scriptService.executable(definition.getScriptType(), definition.getScript(),
                            ImmutableMap.of("logger", logger));
                    executableScript.setNextVar("ctx", ctx);
                    executableScript.run();
                    // we need to unwrap the context object...
                    ctx = (Map<String, Object>) executableScript.unwrap(ctx);
                } catch (Exception e) {
                    logger.warn("failed to script process {}, ignoring", e, ctx);
                    MongoDBRiverHelper.setRiverStatus(client, definition.getRiverName(), Status.SCRIPT_IMPORT_FAILED);
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("Context after script executed: {}", ctx);
                }
                if (isDocumentIgnored(ctx)) {
                    logger.trace("From script ignore document id: {}", objectId);
                    // ignore document
                    return lastTimestamp;
                }
                if (isDocumentDeleted(ctx)) {
                    ctx.put("operation", MongoDBRiver.OPLOG_DELETE_OPERATION);
                }
                if (ctx.containsKey("document")) {
                    data = (Map<String, Object>) ctx.get("document");
                    logger.trace("From script document: {}", data);
                }
                operation = extractOperation(ctx);
                logger.trace("From script operation: {} -> {}", ctx.get("operation").toString(), operation);
            }
        }

        try {
            String index = extractIndex(ctx);
            type = extractType(ctx, type);
            String parent = extractParent(ctx);
            String routing = extractRouting(ctx);
            objectId = extractObjectId(ctx, objectId);
            updateBulkRequest(new BasicDBObject(data), objectId, operation, index, type, routing, parent);
        } catch (IOException e) {
            logger.warn("failed to parse {}", e, entry.getData());
        }
        return lastTimestamp;
    }

    private void updateBulkRequest(DBObject data, String objectId, Operation operation, String index, String type, String routing,
            String parent) throws IOException {
        if (logger.isTraceEnabled()) {
            logger.trace("Operation: {} - index: {} - type: {} - routing: {} - parent: {}", operation, index, type, routing, parent);
        }

        if (operation == Operation.UNKNOWN) {
            logger.error("Unknown operation for id[{}] - entry [{}] - index[{}] - type[{}]", objectId, data, index, type);
            context.setStatus(Status.IMPORT_FAILED);
            return;
        }

        if (operation == Operation.INSERT) {
            if (logger.isTraceEnabled()) {
                logger.trace("Insert operation - id: {} - contains attachment: {}", objectId, (data instanceof GridFSDBFile));
            }
            getBulkProcessor(index, type).addBulkRequest(objectId, build(data, objectId), routing, parent);
        }
        // UPDATE = DELETE + INSERT operation
        if (operation == Operation.UPDATE) {
            if (logger.isTraceEnabled()) {
                logger.trace("Update operation - id: {} - contains attachment: {}", objectId, (data instanceof GridFSDBFile));
            }
            deleteBulkRequest(objectId, index, type, routing, parent);
            getBulkProcessor(index, type).addBulkRequest(objectId, build(data, objectId), routing, parent);
        }
        if (operation == Operation.DELETE) {
            logger.trace("Delete request [{}], [{}], [{}]", index, type, objectId);
            deleteBulkRequest(objectId, index, type, routing, parent);
        }
        if (operation == Operation.DROP_COLLECTION) {
            if (definition.isDropCollection()) {
                MongoDBRiverBulkProcessor processor = getBulkProcessor(index, type);
                processor.dropIndex();
            } else {
                logger.info("Ignore drop collection request [{}], [{}]. The option has been disabled.", index, type);
            }
        }
    }

    /*
     * Delete children when parent / child is used
     */
    private void deleteBulkRequest(String objectId, String index, String type, String routing, String parent) {
        if (logger.isTraceEnabled()) {
            logger.trace("bulkDeleteRequest - objectId: {} - index: {} - type: {} - routing: {} - parent: {}", objectId, index, type,
                    routing, parent);
        }

        if (definition.getParentTypes() != null && definition.getParentTypes().contains(type)) {
            QueryBuilder builder = QueryBuilders.hasParentQuery(type, QueryBuilders.termQuery(MongoDBRiver.MONGODB_ID_FIELD, objectId));
            SearchResponse response = client.prepareSearch(index).setQuery(builder).setRouting(routing)
                    .addField(MongoDBRiver.MONGODB_ID_FIELD).execute().actionGet();
            for (SearchHit hit : response.getHits().getHits()) {
                getBulkProcessor(index, hit.getType()).deleteBulkRequest(hit.getId(), routing, objectId);
            }
        }
        getBulkProcessor(index, type).deleteBulkRequest(objectId, routing, parent);
    }

    @SuppressWarnings("unchecked")
    private Timestamp<?> applyAdvancedTransformation(QueueEntry entry, String type) {

        Timestamp<?> lastTimestamp = entry.getOplogTimestamp();
        Operation operation = entry.getOperation();
        String objectId = "";
        if (entry.getData().get(MongoDBRiver.MONGODB_ID_FIELD) != null) {
            objectId = entry.getData().get(MongoDBRiver.MONGODB_ID_FIELD).toString();
        }
        if (logger.isTraceEnabled()) {
            logger.trace("applyAdvancedTransformation for id: [{}], operation: [{}]", objectId, operation);
        }

        if (!definition.getIncludeCollection().isEmpty()) {
            logger.trace("About to include collection. set attribute {} / {} ", definition.getIncludeCollection(),
                    definition.getMongoCollection());
            entry.getData().put(definition.getIncludeCollection(), definition.getMongoCollection());
        }
        Map<String, Object> ctx = null;
        try {
            ctx = XContentFactory.xContent(XContentType.JSON).createParser("{}").mapAndClose();
        } catch (Exception e) {
        }

        List<Object> documents = new ArrayList<Object>();
        Map<String, Object> document = new HashMap<String, Object>();

        if (hasScript()) {
            if (ctx != null && documents != null) {

                document.put("data", entry.getData().toMap());
                if (!objectId.isEmpty()) {
                    document.put("id", objectId);
                }
                document.put("_index", definition.getIndexName());
                document.put("_type", type);
                document.put("operation", operation.getValue());

                documents.add(document);

                ctx.put("documents", documents);
                try {
                    ExecutableScript executableScript = scriptService.executable(definition.getScriptType(), definition.getScript(),
                            ImmutableMap.of("logger", logger));
                    if (logger.isTraceEnabled()) {
                        logger.trace("Script to be executed: {} - {}", definition.getScriptType(), definition.getScript());
                        logger.trace("Context before script executed: {}", ctx);
                    }
                    executableScript.setNextVar("ctx", ctx);
                    executableScript.run();
                    // we need to unwrap the context object...
                    ctx = (Map<String, Object>) executableScript.unwrap(ctx);
                } catch (Exception e) {
                    logger.error("failed to script process {}, ignoring", e, ctx);
                    MongoDBRiverHelper.setRiverStatus(client, definition.getRiverName(), Status.SCRIPT_IMPORT_FAILED);
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("Context after script executed: {}", ctx);
                }
                if (ctx.containsKey("documents") && ctx.get("documents") instanceof List<?>) {
                    documents = (List<Object>) ctx.get("documents");
                    for (Object object : documents) {
                        if (object instanceof Map<?, ?>) {
                            Map<String, Object> item = (Map<String, Object>) object;
                            if (logger.isTraceEnabled()) {
                                logger.trace("item: {}", item);
                            }
                            if (isDocumentDeleted(item)) {
                                item.put("operation", MongoDBRiver.OPLOG_DELETE_OPERATION);
                            }

                            String index = extractIndex(item);
                            type = extractType(item, type);
                            String parent = extractParent(item);
                            String routing = extractRouting(item);
                            operation = extractOperation(item);
                            boolean ignore = isDocumentIgnored(item);
                            Map<String, Object> data = (Map<String, Object>) item.get("data");
                            objectId = extractObjectId(data, objectId);
                            if (logger.isTraceEnabled()) {
                                logger.trace(
                                        "#### - Id: {} - operation: {} - ignore: {} - index: {} - type: {} - routing: {} - parent: {}",
                                        objectId, operation, ignore, index, type, routing, parent);
                            }
                            if (ignore) {
                                continue;
                            }
                            try {
                                updateBulkRequest(new BasicDBObject(data), objectId, operation, index, type, routing, parent);
                            } catch (IOException ioEx) {
                                logger.error("Update bulk failed.", ioEx);
                            }
                        }
                    }
                }
            }
        }

        return lastTimestamp;
    }

    private XContentBuilder build(final DBObject data, final String objectId) throws IOException {
        if (data instanceof GridFSDBFile) {
            logger.info("Add Attachment: {} to index {} / type {}", objectId, definition.getIndexName(), definition.getTypeName());
            return MongoDBHelper.serialize((GridFSDBFile) data);
        } else {
            Map<String, Object> mapData = this.createObjectMap(data);
            return XContentFactory.jsonBuilder().map(mapData);
        }
    }

    /**
     * Map a DBObject for indexing
     * 
     * @param base
     * @param mapData
     */
    private Map<String, Object> createObjectMap(DBObject base) {
        Map<String, Object> mapData = new HashMap<String, Object>();
        for (String key : base.keySet()) {
            Object forMap = base.get(key);
            if (forMap instanceof DBRef) {
                mapData.put(key, this.convertDbRef((DBRef) forMap));
            } else if (forMap instanceof BasicDBList) {
                mapData.put(key, ((BasicBSONList) forMap).toArray());
            } else if (forMap instanceof BasicDBObject) {
                mapData.put(key, this.createObjectMap((DBObject) forMap));
            } else {
                mapData.put(key, forMap);
            }
        }

        return mapData;
    }

    /**
     * Map a DBRef to a Map for indexing
     * 
     * @param ref
     * @return
     */
    private Map<String, Object> convertDbRef(DBRef ref) {
        Map<String, Object> obj = new HashMap<String, Object>();
        obj.put("id", ref.getId());
        obj.put("ref", ref.getRef());

        return obj;
    }

    private boolean hasScript() {
        return definition.getScriptType() != null && definition.getScript() != null;
    }

    private String extractObjectId(Map<String, Object> ctx, String objectId) {
        Object id = ctx.get("id");
        if (id != null) {
            return id.toString();
        }
        id = ctx.get(MongoDBRiver.MONGODB_ID_FIELD);
        if (id != null) {
            return id.toString();
        } else {
            return objectId;
        }
    }

    private String extractParent(Map<String, Object> ctx) {
        Object parent = ctx.get("_parent");
        if (parent == null) {
            return null;
        } else {
            return parent.toString();
        }
    }

    private String extractRouting(Map<String, Object> ctx) {
        Object routing = ctx.get("_routing");
        if (routing == null) {
            return null;
        } else {
            return routing.toString();
        }
    }

    private Operation extractOperation(Map<String, Object> ctx) {
        Object operation = ctx.get("operation");
        if (operation == null) {
            return null;
        } else {
            return Operation.fromString(ctx.get("operation").toString());
        }
    }

    private boolean isDocumentIgnored(Map<String, Object> ctx) {
        return (ctx.containsKey("ignore") && ctx.get("ignore").equals(Boolean.TRUE));
    }

    private boolean isDocumentDeleted(Map<String, Object> ctx) {
        return (ctx.containsKey("deleted") && ctx.get("deleted").equals(Boolean.TRUE));
    }

    private String extractType(Map<String, Object> ctx, String defaultType) {
        Object type = ctx.get("_type");
        if (type == null) {
            return defaultType;
        } else {
            return type.toString();
        }
    }

    private String extractIndex(Map<String, Object> ctx) {
        String index = (String) ctx.get("_index");
        if (index == null) {
            index = definition.getIndexName();
        }
        return index;
    }

}
