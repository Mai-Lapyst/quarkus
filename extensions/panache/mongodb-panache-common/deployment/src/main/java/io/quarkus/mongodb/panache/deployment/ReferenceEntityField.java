package io.quarkus.mongodb.panache.deployment;

import io.quarkus.deployment.bean.JavaBeanUtil;
import io.quarkus.panache.common.deployment.EntityField;

/**
 * Represents an field that holds the reference data for an {@link ReferencedEntityField}
 * 
 * The underlaying EntityField stores the name of the id field that holds the reference data while
 * this "extension" stores data about the actual field that holds the referenced entity.
 */
public class ReferenceEntityField extends EntityField {

    public final String entity_name;
    public final String entity_descriptor;
    public String entity_signature;

    /**
     * Stores the name of the id field for the Referenced Entity (holded in descriptor)
     */
    public final String bsonIdFieldName;

    public ReferenceEntityField(String name, String descriptor, String entity_name, String entity_descriptor,
            String bsonIdFieldName) {
        super(name, descriptor);
        this.entity_name = entity_name;
        this.entity_descriptor = entity_descriptor;
        this.bsonIdFieldName = bsonIdFieldName;
    }

    public String getReferenceGetterName() {
        return JavaBeanUtil.getGetterName(entity_name, entity_descriptor);
    }

    public String getReferenceSetterName() {
        return JavaBeanUtil.getSetterName(entity_name);
    }

}
