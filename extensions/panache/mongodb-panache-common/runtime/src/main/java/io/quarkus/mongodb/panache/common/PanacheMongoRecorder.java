package io.quarkus.mongodb.panache.common;

import java.util.List;
import java.util.Map;

import io.quarkus.mongodb.panache.common.runtime.MongoIndexUtil;
import io.quarkus.mongodb.panache.common.runtime.MongoPropertyUtil;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class PanacheMongoRecorder {
    public void setReplacementCache(Map<String, Map<String, String>> replacementMap) {
        MongoPropertyUtil.setReplacementCache(replacementMap);
    }

    public void setEntityClassnameCache(List<String> entityClassnameList) {
        MongoIndexUtil.setEntityClassnameCache(entityClassnameList);
    }

    public void ensureIndexes() {
        MongoIndexUtil.ensureIndexes();
    }
}
