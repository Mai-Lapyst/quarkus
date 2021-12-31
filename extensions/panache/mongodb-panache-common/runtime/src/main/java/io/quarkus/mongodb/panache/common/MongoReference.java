package io.quarkus.mongodb.panache.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation can be used to specify some configuration of the mapping of an referenced entity to MongoDB.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MongoReference {
    /**
     * The name of the field that should store the reference
     */
    String store_in() default "";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public static @interface TypeMapping {
        String type();

        String mapped();
    }

    TypeMapping[] typeMappings() default {};
}
