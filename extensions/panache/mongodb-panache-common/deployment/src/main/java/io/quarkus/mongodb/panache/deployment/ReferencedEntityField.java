package io.quarkus.mongodb.panache.deployment;

import java.util.Map;

import io.quarkus.deployment.bean.JavaBeanUtil;
import io.quarkus.panache.common.deployment.EntityField;

/**
 * Represents an field that is referenced.
 * 
 * The underlaying EntityField stores the name of the field that stores the object while
 * this "extension" stores data about the reference field (also called the "id field", that stores the id).
 */
public class ReferencedEntityField extends EntityField {

    public final String id_name;
    public final String id_descriptor;
    public final boolean isContainerWrapped;
    public final String entity_descriptor;
    public final Map<String, String> typeMappings;
    public String id_signature;
    public boolean id_field_exists = false;

    /**
     * Stores the name of the id field for the Referenced Entity (holded in descriptor)
     */
    public final String bsonIdFieldName;

    public ReferencedEntityField(String name, String descriptor, String id_name, String id_descriptor, String bsonIdFieldName,
            boolean isContainerWrapped, String entity_descriptor, Map<String, String> typeMappings) {
        super(name, descriptor);
        this.id_name = id_name;
        this.id_descriptor = id_descriptor;
        this.bsonIdFieldName = bsonIdFieldName;
        this.isContainerWrapped = isContainerWrapped;
        this.entity_descriptor = entity_descriptor;
        this.typeMappings = typeMappings;
    }

    public String getIdGetterName() {
        return JavaBeanUtil.getGetterName(id_name, id_descriptor);
    }

    public String getIdSetterName() {
        return JavaBeanUtil.getSetterName(id_name);
    }

}
