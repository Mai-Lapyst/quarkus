package io.quarkus.mongodb.panache.common;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation can be used to specify some configuration of the index for mongodb.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface MongoIndex {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    public static @interface Key {
        public static enum Type {
            ASC,
            DESC;
        };

        String value();

        Type type() default Type.ASC;
    }

    /**
     * keys this index operates on
     */
    Key[] keys();

    boolean background() default false;

    boolean unique() default false;

    String name() default "";

    boolean sparse() default false;

    long expireAfterSeconds() default -1;

    boolean hidden() default false;

}
