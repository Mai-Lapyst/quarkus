package io.quarkus.mongodb.panache.deployment;

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
    public String id_signature;
    public boolean id_field_exists = false;

    /**
     * Stores the name of the id field for the Referenced Entity (holded in descriptor)
     */
    public final String bsonIdFieldName;

    public ReferencedEntityField(String name, String descriptor, String id_name, String id_descriptor, String bsonIdFieldName) {
        super(name, descriptor);
        this.id_name = id_name;
        this.id_descriptor = id_descriptor;
        this.bsonIdFieldName = bsonIdFieldName;
    }

    public String getIdGetterName() {
        return JavaBeanUtil.getGetterName(id_name, id_descriptor);
    }

    public String getIdSetterName() {
        return JavaBeanUtil.getSetterName(id_name);
    }

}
