package io.quarkus.mongodb.panache.common.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.conversions.Bson;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.common.MongoIndex;

public final class MongoIndexUtil {

    // will be replaced at augmentation phase
    private static volatile List<String> entityClassnameCache = Collections.emptyList();

    public static void setEntityClassnameCache(List<String> newEntityClassnameCache) {
        entityClassnameCache = newEntityClassnameCache;
    }

    private static String getDefaultDatabaseName(MongoEntity mongoEntity) {
        return BeanUtils.getDatabaseName(mongoEntity, BeanUtils.beanName(mongoEntity));
    }

    private static MongoDatabase mongoDatabase(MongoEntity mongoEntity) {
        MongoClient mongoClient = BeanUtils.clientFromArc(mongoEntity, MongoClient.class, false);
        if (mongoEntity != null && !mongoEntity.database().isEmpty()) {
            return mongoClient.getDatabase(mongoEntity.database());
        }
        String databaseName = getDefaultDatabaseName(mongoEntity);
        return mongoClient.getDatabase(databaseName);
    }

    public static MongoCollection mongoCollection(Class<?> entityClass) {
        MongoEntity mongoEntity = entityClass.getAnnotation(MongoEntity.class);
        MongoDatabase database = mongoDatabase(mongoEntity);
        if (mongoEntity != null && !mongoEntity.collection().isEmpty()) {
            return database.getCollection(mongoEntity.collection(), entityClass);
        }
        return database.getCollection(entityClass.getSimpleName(), entityClass);
    }

    private static void ensureEntityIndexs(String className) throws ClassNotFoundException {
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> entityClass = cl.loadClass(className);

        MongoEntity mongoEntity = entityClass.getAnnotation(MongoEntity.class);
        if (mongoEntity == null) {
            return;
        }

        MongoCollection mongoCollection = mongoCollection(entityClass);
        for (MongoIndex mongoIndex : mongoEntity.indexes()) {
            List<Bson> keys = new ArrayList<>(mongoIndex.keys().length);
            for (MongoIndex.Key key : mongoIndex.keys()) {
                switch (key.type()) {
                    case ASC: {
                        keys.add(Indexes.ascending(key.value()));
                        break;
                    }
                    case DESC: {
                        keys.add(Indexes.descending(key.value()));
                        break;
                    }
                    default: {
                        throw new RuntimeException(
                                "Unknown type for index key '" + key.value() + "': " + mongoIndex.keys().toString());
                    }
                }
            }

            IndexOptions options = new IndexOptions();
            options.background(mongoIndex.background());
            options.unique(mongoIndex.unique());
            if (!mongoIndex.name().isEmpty()) {
                options.name(mongoIndex.name());
            }
            options.sparse(mongoIndex.sparse());
            if (mongoIndex.expireAfterSeconds() > 0) {
                options.expireAfter(mongoIndex.expireAfterSeconds(), TimeUnit.SECONDS);
            }
            options.hidden(mongoIndex.hidden());

            if (keys.size() > 0) {
                mongoCollection.createIndex(Indexes.compoundIndex(keys), options);
            } else {
                mongoCollection.createIndex(keys.get(0), options);
            }
        }

    }

    public static void ensureIndexes() {
        for (String className : entityClassnameCache) {
            try {
                ensureEntityIndexs(className);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

}
